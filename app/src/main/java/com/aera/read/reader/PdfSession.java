package com.aera.read.reader;

import android.content.ComponentCallbacks2;
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

/** PDF reading: pages streamed from the document, zoom, bookmarks and resume. */
public class PdfSession implements ReadingSession {

    /** Overridable for tests that cannot run the native PDF renderer. */
    public static PdfPageSource.Factory sourceFactory = PdfRendererSource::open;

    private static final int MAX_RENDER_WIDTH = 2400;
    private static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;

    private final Context ctx;
    private final Book book;
    private PdfPageSource source;
    private ReaderTheme theme = ReaderTheme.PAPER;
    private int pageCount;

    private final LruCache<String, Bitmap> cache;
    private final Map<String, List<Waiter>> inFlight = new HashMap<>();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aera-pdf-render");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private int renderWidth = 1000;
    private volatile int activePage;
    private volatile boolean closed;
    private final int margin;

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
        margin = Ui.dp(ctx, 8);
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
        return session;
    }

    @Override
    public void layout(ReaderPrefs prefs, ReaderTheme theme, int frameWidth, int frameHeight) {
        this.theme = theme;
        renderWidth = Math.max(320, Math.min(frameWidth - 2 * margin, 1600));
    }

    @Override
    public int initialPage() {
        return clamp(book.offset);
    }

    @Override
    public float initialFraction() {
        return 0f;
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
        return false;
    }

    @Override
    public View createItemView(ViewGroup parent, boolean scrollMode) {
        ZoomImageView view = new ZoomImageView(parent.getContext(), scrollMode);
        view.setTheme(theme);
        return view;
    }

    @Override
    public void bindItemView(View view, int page, int itemHeightPx, boolean scrollMode) {
        activePage = page;
        final ZoomImageView imageView = (ZoomImageView) view;
        imageView.setTheme(theme);
        final int tag = page;
        final int width = renderWidth;
        imageView.setTag(tag);
        BooleanSupplier stillBound = () -> !closed && Integer.valueOf(tag).equals(imageView.getTag());
        String key = page + "@" + width;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            imageView.show(page, cached);
        } else {
            request(page, width, stillBound, bitmap -> {
                if (Integer.valueOf(tag).equals(imageView.getTag())) imageView.show(page, bitmap);
            });
        }
        // magnifying a PDF page only stays readable if the page is rendered again at
        // that size; the view asks for a level and swaps the bitmap in place
        imageView.setZoomListener(level -> {
            if (!Integer.valueOf(tag).equals(imageView.getTag())) return;
            int wanted = Math.min(MAX_RENDER_WIDTH, width * level);
            request(page, wanted, stillBound, bitmap -> {
                if (bitmap != null && Integer.valueOf(tag).equals(imageView.getTag())) {
                    imageView.replaceBitmap(bitmap);
                }
            });
        });
        if (!scrollMode && page + 1 < pageCount) {
            final int next = page + 1;
            request(next, width, () -> !closed && Math.abs(next - activePage) <= 2, null);
        }
    }

    private interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    private void request(int page, int width, BooleanSupplier needed, BitmapCallback callback) {
        if (closed) return;
        final String key = page + "@" + width;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            if (callback != null) callback.onBitmap(cached);
            return;
        }
        synchronized (inFlight) {
            if (closed) return;
            List<Waiter> waiters = inFlight.get(key);
            if (waiters != null) {
                // Attach to the already-queued render instead of rendering the same page twice.
                waiters.add(new Waiter(needed, callback));
                return;
            }
            waiters = new ArrayList<>(2);
            waiters.add(new Waiter(needed, callback));
            inFlight.put(key, waiters);
        }
        try {
            renderExecutor.execute(() -> runRender(key, page, width));
        } catch (RuntimeException rejected) {
            synchronized (inFlight) {
                inFlight.remove(key);
            }
        }
    }

    private void runRender(String key, int page, int width) {
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
                // Re-check under lock in case a fresh viewer attached right before removal.
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
                bitmap = src.render(page, width);
            } catch (Exception ignored) {
            }
        }
        List<Waiter> waiters;
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
    public boolean needsRelayout(ReaderPrefs prefs) {
        return false;
    }

    @Override
    public int relayout(ReaderPrefs prefs, int frameWidth, int frameHeight, int currentPage,
                        float currentFraction) {
        renderWidth = Math.max(320, Math.min(frameWidth - 2 * margin, 1600));
        return clamp(currentPage);
    }

    @Override
    public void persistPosition(int page, float fraction, int frameHeight) {
        int index = clamp(page);
        book.chapter = -1;
        book.offset = index;
        book.fraction = 0f;
        book.progress = (index + 1) / (float) Math.max(1, pageCount);
        Library.updatePosition(ctx, book.id(), -1, index, 0f, book.progress);
    }

    // ---------------------------------------------------------------- bookmarks

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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.evictAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.maxSize() / 2);
        }
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
