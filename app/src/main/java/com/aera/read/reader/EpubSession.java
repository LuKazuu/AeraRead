package com.aera.read.reader;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.aera.read.R;
import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.ImageStore;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.BookFiles;
import com.aera.read.io.SeekableSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.ui.PageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EpubSession implements ReadingSession {

    private final Context ctx;
    private final Book book;
    private EpubBook epub;
    private ImageStore images;
    private PagedBook pages;
    private ReaderTheme theme;
    private ReaderPrefs prefs;

    /** Chapter index -> the name the table of contents uses for it, when it lists one. */
    private Map<Integer, String> tocTitles;
    /** Top-level contents rows; sub-entries only ever drop you mid-chapter. */
    private List<EpubBook.TocEntry> contents;

    /**
     * Spot the last re-layout anchored on. Reusing it keeps a run of font changes from
     * drifting, and it is dropped the moment the reader actually turns a page, so it can
     * never pull anybody back to an old place.
     */
    private boolean anchorPinned;
    private int anchorChapter = -1;
    private int anchorOffset;

    /** Where the last re-layout put the reader, so saving keeps that spot, not the page top. */
    private int mappedChapter = -1;
    private int mappedOffset;
    private int mappedPage = -1;

    private String cachedFont;
    private int cachedSize;
    private int cachedLine;
    private int frameW;
    private int frameH;

    private final int padH;
    private final int padTop;
    private final int padBottom;

    private EpubSession(Context c, Book b) {
        this.ctx = c.getApplicationContext();
        this.book = b;
        padH = ctx.getResources().getDimensionPixelSize(R.dimen.reader_pad_h);
        padTop = ctx.getResources().getDimensionPixelSize(R.dimen.reader_pad_top);
        padBottom = ctx.getResources().getDimensionPixelSize(R.dimen.reader_pad_bottom);
    }

    /** Opens the book where it lives; nothing is copied into the app. */
    public static EpubSession open(Context c, Book book) throws Exception {
        EpubSession session = new EpubSession(c, book);
        SeekableSource source = BookFiles.openSeekable(session.ctx, book);
        ZipArchive zip = ZipArchive.open(source);
        try {
            EpubParser parser = new EpubParser(zip);
            session.epub = parser.parse(book.title);
            // Cover thumbnails live in Thumbnails; keeping raw cover bytes in RAM wastes 1-5 MB.
            session.epub.cover = null;
            session.images = new ImageStore(zip);
            session.indexTocTitles();
            session.buildContents();
            return session;
        } catch (Exception e) {
            zip.close();
            throw e;
        }
    }

    private void indexTocTitles() {
        tocTitles = new HashMap<>();
        for (EpubBook.TocEntry entry : epub.toc) {
            if (!entry.title.isEmpty() && !tocTitles.containsKey(entry.chapter)) {
                tocTitles.put(entry.chapter, entry.title.replaceAll("\\s+", " ").trim());
            }
        }
    }

    @Override
    public void layout(ReaderPrefs p, ReaderTheme t, int frameWidth, int frameHeight) {
        prefs = p;
        theme = t;
        frameW = frameWidth;
        frameH = frameHeight;
        TextStyles styles = TextStyles.create(ctx, p);
        cacheStyle(p);
        int w = Math.max(80, frameWidth - 2 * padH);
        int h = contentHeight(frameHeight);
        pages = EpubPaginator.paginate(epub, styles, w, h);
    }

    private int contentHeight(int frameHeight) {
        return Math.max(80, frameHeight - padTop - padBottom);
    }

    private void cacheStyle(ReaderPrefs p) {
        cachedFont = p.font;
        cachedSize = p.sizeSp;
        cachedLine = p.linePct;
    }

    private String layoutKey(ReaderPrefs p, int width, int height) {
        return width + "x" + height + "-" + p.font + "-" + p.sizeSp + "-" + p.linePct;
    }

    @Override
    public int initialPage() {
        return pages.pageForAnchor(book.chapter, book.offset);
    }

    @Override
    public float initialFraction() {
        int page = initialPage();
        String savedKey = book.layoutKey;
        if (savedKey != null && !savedKey.isEmpty()
                && savedKey.equals(layoutKey(prefs, frameW, frameH))) {
            return Math.max(0f, Math.min(0.98f, book.fraction));
        }
        return PagedBook.fractionOf(pages.page(page), book.offset);
    }

    /** Character at the top of a page; used by tests to check what the reader can see. */
    public int offsetAtTopOf(int page) {
        if (pages == null) return 0;
        return pages.page(Math.max(0, Math.min(pages.totalPages - 1, page))).startOffset;
    }

    @Override
    public String title() {
        return epub.title;
    }

    /** Pagination snapshot; package-private so tests can verify exact resume. */
    PagedBook paged() {
        return pages;
    }

    @Override
    public int pageCount() {
        return pages == null ? 0 : pages.totalPages;
    }

    @Override
    public String pageLabel(int page) {
        return ctx.getString(R.string.page_of, page + 1, pageCount());
    }

    @Override
    public String percentLabel(int page) {
        return ctx.getString(R.string.percent, Math.round(100f * (page + 1)
                / Math.max(1, pageCount())));
    }

    @Override
    public boolean supportsTypography() {
        return true;
    }

    @Override
    public boolean supportsContents() {
        return true;
    }

    @Override
    public boolean supportsViewMode() {
        return true;
    }

    @Override
    public View createItemView(ViewGroup parent, boolean scrollMode) {
        PageView view = new PageView(parent.getContext(), scrollMode);
        view.setPadding(padH, padTop, padH, padBottom);
        return view;
    }

    @Override
    public void bindItemView(View view, int page, int itemHeightPx, boolean scrollMode) {
        PageView pv = (PageView) view;
        if (pages != null && page >= 0 && page < pages.totalPages) {
            pv.setData(pages, pages.page(page), theme, images);
        }
    }

    @Override
    public void setTheme(ReaderTheme theme) {
        this.theme = theme;
    }

    @Override
    public boolean needsRelayout(ReaderPrefs p) {
        return !p.font.equals(cachedFont) || p.sizeSp != cachedSize || p.linePct != cachedLine;
    }

    @Override
    public int relayout(ReaderPrefs p, int frameWidth, int frameHeight, int currentPage,
                        float currentFraction) {
        int chapter;
        int offset;
        if (anchorChapter >= 0) {
            chapter = anchorChapter;
            offset = anchorOffset;
        } else {
            PagedBook.Page old = pages.page(currentPage);
            chapter = old.chapter;
            offset = pages.offsetAt(currentPage,
                    Math.max(0, currentFraction * frameHeight - padTop));
        }

        frameW = frameWidth;
        frameH = frameHeight;
        prefs = p;
        TextStyles styles = TextStyles.create(ctx, p);
        cacheStyle(p);
        int w = Math.max(80, frameWidth - 2 * padH);
        pages = EpubPaginator.paginate(epub, styles, w, contentHeight(frameHeight));
        // hold this spot for the next change, and never re-derive it from a moved page
        anchorChapter = chapter;
        anchorOffset = offset;
        int mapped = pages.pageForAnchor(chapter, offset);
        mappedChapter = chapter;
        mappedOffset = offset;
        mappedPage = mapped;
        book.layoutKey = "";
        return mapped;
    }

    @Override
    public void pinAnchor(int page, float fraction, int frameHeight) {
        if (pages == null) return;
        PagedBook.Page p = pages.page(page);
        anchorChapter = p.chapter;
        anchorOffset = pages.offsetAt(page, Math.max(0, fraction * frameHeight - padTop));
        anchorPinned = true;
    }

    @Override
    public void clearAnchor() {
        anchorPinned = false;
        anchorChapter = -1;
        mappedChapter = -1;
        mappedPage = -1;
    }

    /** A page was turned or a jump was made: the held spot is no longer the reader's. */
    @Override
    public void noteNavigation() {
        if (anchorPinned) return;
        clearAnchor();
    }

    // ---------------------------------------------------------------- position

    @Override
    public void persistPosition(int page, float fraction, int frameHeight) {
        if (pages == null || pages.totalPages <= 0) return;
        int index = Math.max(0, Math.min(pages.totalPages - 1, page));
        PagedBook.Page p = pages.page(index);
        float f = Math.max(0f, Math.min(0.98f, fraction));
        int offset = p.startOffset;
        if (f <= 0.001f && index == mappedPage && p.chapter == mappedChapter
                && mappedOffset >= p.startOffset && mappedOffset < p.endOffset) {
            // just re-laid out: remember the spot the reader was holding, not the page top
            offset = mappedOffset;
        }
        if (f > 0.001f) {
            float y = Math.max(0, f * frameHeight - padTop);
            offset = pages.offsetAt(index, y);
            offset = Math.max(p.startOffset,
                    Math.min(offset, Math.max(p.startOffset, p.endOffset - 1)));
        }
        book.chapter = p.chapter;
        book.offset = offset;
        book.fraction = f;
        book.layoutKey = frameHeight > 0 ? layoutKey(prefs, frameW, frameHeight) : "";
        book.progress = Math.min(1f, (index + f) / pages.totalPages);
        Library.updatePosition(ctx, book.id(), p.chapter, offset, f, book.progress,
                book.layoutKey);
    }

    // ---------------------------------------------------------------- bookmarks

    @Override
    public boolean isBookmarked(int page) {
        if (pages == null) return false;
        PagedBook.Page p = pages.page(page);
        return findBookmark(p) != null;
    }

    private Bookmark findBookmark(PagedBook.Page p) {
        for (Bookmark bm : Library.bookmarks(ctx, book.id())) {
            if (bm.chapter == p.chapter && bm.offset >= p.startOffset
                    && bm.offset < Math.max(p.endOffset, p.startOffset + 1)) {
                return bm;
            }
        }
        return null;
    }

    @Override
    public void toggleBookmark(int page) {
        if (pages == null) return;
        PagedBook.Page p = pages.page(page);
        Bookmark existing = findBookmark(p);
        if (existing != null) {
            Library.removeBookmark(ctx, book.id(), existing);
        } else {
            Library.addBookmark(ctx, book.id(), new Bookmark(bookmarkTitle(page), p.chapter,
                    p.startOffset, System.currentTimeMillis()));
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
        String chapter = chapterLabel(page);
        String name = chapter.isEmpty() ? ctx.getString(R.string.page_n, page + 1) : chapter;
        return name + " · " + percentLabel(page);
    }

    /**
     * Name of the chapter a page belongs to, but only when the table of contents lists
     * it. Spine files the contents never mentions get no name rather than a made-up one.
     */
    private String chapterLabel(int page) {
        if (pages == null || tocTitles == null) return "";
        PagedBook.Page p = pages.page(page);
        String title = tocTitles.get(p.chapter);
        return title == null ? "" : title;
    }

    @Override
    public int pageOfBookmark(Bookmark bookmark) {
        return pages.pageIndex(bookmark.chapter, bookmark.offset);
    }

    @Override
    public void deleteBookmark(Bookmark bookmark) {
        Library.removeBookmark(ctx, book.id(), bookmark);
    }

    // ---------------------------------------------------------------- contents

    /**
     * Keeps the entries at the shallowest level the contents uses: chapters, not the
     * sections nested inside them. A book that wraps everything under a single heading
     * needs the level below instead, so the list is never a one-row stub.
     */
    private void buildContents() {
        contents = topLevel(epub.toc);
    }

    static List<EpubBook.TocEntry> topLevel(List<EpubBook.TocEntry> toc) {
        List<EpubBook.TocEntry> out = new ArrayList<>();
        for (int level = 1; level <= 3; level++) {
            for (EpubBook.TocEntry entry : toc) {
                if (entry.level == level) out.add(entry);
            }
            if (out.size() >= 2) return out;
            out.clear();
        }
        out.addAll(toc);
        return out;
    }

    @Override
    public List<EpubBook.TocEntry> contents() {
        return contents;
    }

    @Override
    public int pageOfContents(int index) {
        if (contents.isEmpty()) return 0;
        EpubBook.TocEntry entry = contents.get(Math.max(0,
                Math.min(index, contents.size() - 1)));
        return pages.pageIndex(entry.chapter, 0);
    }

    @Override
    public void trimMemory(int level) {
        if (images != null) images.trimMemory(level);
    }

    @Override
    public void close() {
        final EpubBook openBook = epub;
        epub = null;
        if (images != null) {
            ImageStore store = images;
            images = null;
            store.close(() -> {
                if (openBook != null) openBook.close();
            });
        } else if (openBook != null) {
            openBook.close();
        }
    }
}
