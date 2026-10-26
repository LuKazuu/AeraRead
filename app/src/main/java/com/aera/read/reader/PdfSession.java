package com.aera.read.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;

import com.aera.read.R;
import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.pdf.PdfPageSource;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.ui.ZoomImageView;
import com.aera.read.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** PDF reading: sharp margin-trimmed pages, full-page or background theming, zoom, bookmarks. */
public class PdfSession implements ReadingSession {

    /** Overridable for tests that supply custom PDF sources. */
    public static PdfPageSource.Factory sourceFactory = PdfRendererSource::open;

    private static final int MAX_RENDER_WIDTH = 2400;
    private static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;

    private final Context ctx;
    private final Book book;
    private PdfPageSource source;
    private ReaderTheme theme = ReaderTheme.LIGHT;
    private boolean fullPageTheme = true;
    private volatile String trimMode = ReaderPrefs.TRIM_NORMAL;
    private int pageCount;

    private final LruCache<String, Bitmap> cache;
    private final Map<String, List<Waiter>> inFlight = new HashMap<>();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aera-pdf-render");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private int renderWidth = 1080;
    private int frameW = 1080;
    private final Object ratioLock = new Object();
    private float[] pageRatios;
    private float defaultPageRatio = ZoomImageView.DEFAULT_PAGE_RATIO;
    private volatile int activePage;
    private volatile boolean closed;

    private static final class Waiter {
        final BooleanSupplier needed;
        final BitmapCallback callback;

        Waiter(BooleanSupplier needed, BitmapCallback callback) {
            this.needed = needed;
            this.callback = callback;
        }
    }

    private PdfSession(Context c, Book b) {
        ctx = c.getApplicationContext();
        book = b;
        long maxHeap = Runtime.getRuntime().maxMemory();
        int budget = (int) Math.max(4 * 1024 * 1024L,
                Math.min(MAX_CACHE_BYTES, maxHeap / 8));
        cache = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    }

    public static PdfSession open(Context c, Book book) throws Exception {
        PdfSession session = new PdfSession(c, book);
        session.source = sourceFactory.open(c, book);
        session.pageCount = session.source.pageCount();
        session.pageRatios = new float[Math.max(1, session.pageCount)];
        return session;
    }

    @Override
    public void layout(ReaderPrefs prefs, ReaderTheme theme, int frameWidth, int frameHeight) {
        this.theme = theme;
        this.fullPageTheme = prefs.pdfFullTheme;
        this.frameW = Math.max(1, frameWidth);
        applyTrimMode(prefs.pdfTrim);
        updateRenderWidth(frameWidth);
        if (prefs.isScroll() && pageCount > 0) {
            int start = initialPage();
            prewarmSync(start, trimMode);
            if (start + 1 < pageCount) prewarmSync(start + 1, trimMode);
        }
    }

    private void applyTrimMode(String requested) {
        String next = ReaderPrefs.normalizeTrim(requested);
        if (next.equals(trimMode)) {
            PdfPageSource src = source;
            if (src != null) src.setTrimMode(next);
            return;
        }
        synchronized (ratioLock) {
            trimMode = next;
            pageRatios = new float[Math.max(1, pageCount)];
            defaultPageRatio = ZoomImageView.DEFAULT_PAGE_RATIO;
        }
        PdfPageSource src = source;
        if (src != null) src.setTrimMode(next);
    }

    private static String cacheKey(int page, int width, String mode) {
        return page + "@" + width + "@" + mode;
    }

    private void updateRenderWidth(int frameWidth) {
        // Supersample 1.35x above viewport width (capped so pinch-zoom up to 2400px can sharpen
        // even further) so unzoomed PDF body text and diagrams are razor-sharp on screen.
        renderWidth = Math.max(360, Math.min(Math.round(frameWidth * 1.35f), 1800));
    }

    private void prewarmSync(int page, String mode) {
        if (closed || page < 0 || page >= pageCount) return;
        int width = renderWidth;
        String key = cacheKey(page, width, mode);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            recordRatio(page, cached, mode);
            return;
        }
        PdfPageSource src = source;
        if (src == null) return;
        try {
            Bitmap rendered;
            synchronized (src) {
                src.setTrimMode(mode);
                rendered = src.render(page, width);
            }
            if (rendered != null && !closed) {
                recordRatio(page, rendered, mode);
                synchronized (inFlight) {
                    if (!closed) cache.put(key, rendered);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void recordRatio(int page, Bitmap bmp, String mode) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
        float ratio = bmp.getHeight() / (float) bmp.getWidth();
        synchronized (ratioLock) {
            if (!mode.equals(trimMode)) return;
            if (pageRatios != null && page >= 0 && page < pageRatios.length) {
                pageRatios[page] = ratio;
            }
            if (page > 0 || defaultPageRatio == ZoomImageView.DEFAULT_PAGE_RATIO) {
                defaultPageRatio = ratio;
            }
        }
    }

    private float ratioForPage(int page) {
        synchronized (ratioLock) {
            if (pageRatios != null && page >= 0 && page < pageRatios.length
                    && pageRatios[page] > 0.05f) {
                return pageRatios[page];
            }
            return defaultPageRatio;
        }
    }

    @Override
    public int scrollPageHeight(int page, int frameWidth, int frameHeight) {
        int w = frameWidth > 0 ? frameWidth : frameW;
        String mode = trimMode;
        boolean maxTrim = ReaderPrefs.TRIM_MAX.equals(mode);
        if (pageCount > 0) {
            int clamped = clamp(page);
            boolean known;
            synchronized (ratioLock) {
                known = pageRatios != null && clamped < pageRatios.length
                        && pageRatios[clamped] > 0.05f;
            }
            if (!known) prewarmSync(clamped, mode);
            return ZoomImageView.scrollItemHeightPx(ctx, w, ratioForPage(clamped), maxTrim);
        }
        return ZoomImageView.scrollItemHeightPx(ctx, w, defaultPageRatio, maxTrim);
    }

    @Override
    public int initialPage() {
        return clamp(book.offset);
    }

    @Override
    public float initialFraction() {
        return Math.max(0f, Math.min(0.999f, book.fraction));
    }

    private int clamp(int page) {
        return Math.max(0, Math.min(Math.max(0, pageCount - 1), page));
    }

    @Override
    public String title() {
        return book.title;
    }

    @Override
    public int pageCount() {
        return pageCount;
    }

    @Override
    public String pageLabel(int page) {
        return ctx.getString(R.string.page_of, page + 1, pageCount);
    }

    @Override
    public String percentLabel(int page) {
        return ctx.getString(R.string.percent,
                Math.round(100f * (page + 1) / Math.max(1, pageCount)));
    }

    @Override
    public boolean supportsTypography() {
        return false;
    }

    @Override
    public boolean supportsContents() {
        return false;
    }

    @Override
    public boolean supportsViewMode() {
        return true;
    }

    @Override
    public View createItemView(ViewGroup parent, boolean scrollMode) {
        ZoomImageView view = new ZoomImageView(parent.getContext(), scrollMode);
        view.setTheme(theme, fullPageTheme);
        return view;
    }

    @Override
    public void bindItemView(View view, int page, int itemHeightPx, boolean scrollMode) {
        activePage = page;
        final ZoomImageView imageView = (ZoomImageView) view;
        final String mode = trimMode;
        imageView.setTheme(theme, fullPageTheme);
        imageView.preparePage(page, ratioForPage(page), mode);
        final String tag = page + "@" + mode;
        final int width = renderWidth;
        imageView.setTag(tag);
        BooleanSupplier stillBound = () -> !closed && tag.equals(imageView.getTag());
        String key = cacheKey(page, width, mode);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            recordRatio(page, cached, mode);
            imageView.show(page, cached);
        } else {
            request(page, width, mode, stillBound, bitmap -> {
                if (tag.equals(imageView.getTag())) imageView.show(page, bitmap);
            });
        }
        imageView.setZoomListener(level -> {
            if (!tag.equals(imageView.getTag())) return;
            int wanted = Math.min(MAX_RENDER_WIDTH, width * level);
            request(page, wanted, mode, stillBound, bitmap -> {
                if (bitmap != null && tag.equals(imageView.getTag())) {
                    imageView.replaceBitmap(bitmap);
                }
            });
        });
        if (page + 1 < pageCount) {
            final int next = page + 1;
            request(next, width, mode,
                    () -> !closed && mode.equals(trimMode) && Math.abs(next - activePage) <= 2,
                    null);
        }
        if (page > 0) {
            final int prev = page - 1;
            request(prev, width, mode,
                    () -> !closed && mode.equals(trimMode) && Math.abs(prev - activePage) <= 2,
                    null);
        }
    }

    private interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    private void request(int page, int width, String mode, BooleanSupplier needed,
                         BitmapCallback callback) {
        if (closed) return;
        final String key = cacheKey(page, width, mode);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onBitmap(cached);
            return;
        }
        synchronized (inFlight) {
            if (closed) return;
            List<Waiter> waiters = inFlight.get(key);
            if (waiters != null) {
                waiters.add(new Waiter(needed, callback));
                return;
            }
            waiters = new ArrayList<>(2);
            waiters.add(new Waiter(needed, callback));
            inFlight.put(key, waiters);
        }
        try {
            renderExecutor.execute(() -> runRender(key, page, width, mode));
        } catch (RuntimeException rejected) {
            synchronized (inFlight) {
                inFlight.remove(key);
            }
        }
    }

    private void runRender(String key, int page, int width, String mode) {
        List<Waiter> snapshot;
        synchronized (inFlight) {
            List<Waiter> registered = inFlight.get(key);
            if (registered == null || closed) {
                inFlight.remove(key);
                return;
            }
            snapshot = new ArrayList<>(registered);
        }
        boolean anyNeeded = false;
        for (Waiter w : snapshot) {
            if (w.needed == null || w.needed.getAsBoolean()) {
                anyNeeded = true;
                break;
            }
        }
        if (!anyNeeded) {
            synchronized (inFlight) {
                List<Waiter> current = inFlight.get(key);
                if (current != null && current.size() == snapshot.size()) {
                    inFlight.remove(key);
                    return;
                }
            }
        }

        Bitmap bitmap = null;
        PdfPageSource src = source;
        if (!closed && src != null) {
            try {
                synchronized (src) {
                    src.setTrimMode(mode);
                    bitmap = src.render(page, width);
                }
            } catch (Exception ignored) {
            }
        }
        List<Waiter> waiters;
        if (bitmap != null && !closed) {
            recordRatio(page, bitmap, mode);
        }
        synchronized (inFlight) {
            waiters = inFlight.remove(key);
            if (bitmap != null && !closed) {
                cache.put(key, bitmap);
            }
        }
        if (closed || waiters == null) return;
        final Bitmap result = bitmap;
        main.post(() -> {
            if (closed) return;
            for (Waiter w : waiters) {
                if (w.callback != null) w.callback.onBitmap(result);
            }
        });
    }

    @Override
    public void setTheme(ReaderTheme theme) {
        this.theme = theme;
    }

    @Override
    public void setPdfFullTheme(boolean fullPage) {
        this.fullPageTheme = fullPage;
    }

    @Override
    public boolean needsRelayout(ReaderPrefs prefs) {
        return !trimMode.equals(ReaderPrefs.normalizeTrim(prefs.pdfTrim));
    }

    @Override
    public int relayout(ReaderPrefs prefs, int frameWidth, int frameHeight, int currentPage,
                        float currentFraction) {
        this.fullPageTheme = prefs.pdfFullTheme;
        this.frameW = Math.max(1, frameWidth);
        applyTrimMode(prefs.pdfTrim);
        updateRenderWidth(frameWidth);
        int target = clamp(currentPage);
        if (pageCount > 0) {
            String mode = trimMode;
            prewarmSync(target, mode);
            if (prefs.isScroll() && target + 1 < pageCount) prewarmSync(target + 1, mode);
        }
        return target;
    }

    @Override
    public void persistPosition(int page, float fraction, int frameHeight) {
        int index = clamp(page);
        float clampedFraction = Math.max(0f, Math.min(0.999f, fraction));
        book.chapter = -1;
        book.offset = index;
        book.fraction = clampedFraction;
        book.progress = (index + 1) / (float) Math.max(1, pageCount);
        Library.updatePosition(ctx, book.id(), -1, index, clampedFraction, book.progress);
    }

    @Override
    public boolean isBookmarked(int page) {
        for (Bookmark bm : Library.bookmarks(ctx, book.id())) {
            if (bm.chapter == -1 && bm.offset == page) return true;
        }
        return false;
    }

    @Override
    public void toggleBookmark(int page) {
        Bookmark existing = null;
        for (Bookmark bm : Library.bookmarks(ctx, book.id())) {
            if (bm.chapter == -1 && bm.offset == page) {
                existing = bm;
                break;
            }
        }
        if (existing != null) {
            Library.removeBookmark(ctx, book.id(), existing);
        } else {
            Library.addBookmark(ctx, book.id(),
                    new Bookmark(bookmarkTitle(page), -1, page, System.currentTimeMillis()));
        }
    }

    @Override
    public List<Bookmark> bookmarks() {
        List<Bookmark> marks = Library.bookmarks(ctx, book.id());
        marks.sort((a, b) -> Long.compare(b.created, a.created));
        return marks;
    }

    @Override
    public String bookmarkTitle(int page) {
        return ctx.getString(R.string.page_n, page + 1) + " · " + percentLabel(page);
    }

    @Override
    public int pageOfBookmark(Bookmark bookmark) {
        return clamp(bookmark.offset);
    }

    @Override
    public void deleteBookmark(Bookmark bookmark) {
        Library.removeBookmark(ctx, book.id(), bookmark);
    }

    @Override
    public List<EpubBook.TocEntry> contents() {
        return Collections.emptyList();
    }

    @Override
    public int pageOfContents(int index) {
        return 0;
    }

    @Override
    public void trimMemory(int level) {
        if (closed) return;
        Ui.trimCache(cache, level);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        synchronized (inFlight) {
            inFlight.clear();
        }
        renderExecutor.shutdownNow();
        cache.evictAll();
        PdfPageSource src = source;
        source = null;
        if (src != null) {
            try {
                src.close();
            } catch (Exception ignored) {
            }
        }
    }
}
