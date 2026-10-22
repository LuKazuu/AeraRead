package com.aera.read.reader;

import android.view.View;
import android.view.ViewGroup;

import com.aera.read.data.Bookmark;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;

import java.util.List;

/** Uniform API the reader UI uses to drive an EPUB or a PDF. */
public interface ReadingSession extends AutoCloseable {

    String title();

    int pageCount();

    String pageLabel(int page);

    String percentLabel(int page);

    /** EPUB only: fonts, size and line spacing. PDFs render their own typography. */
    boolean supportsTypography();

    /** EPUB only: table of contents. */
    boolean supportsContents();

    /** EPUB only: scroll vs paged reading. PDFs are always paged. */
    boolean supportsViewMode();

    int initialPage();

    /** 0..1 offset inside the first visible page (scroll mode resume). */
    float initialFraction();

    void layout(ReaderPrefs prefs, ReaderTheme theme, int frameWidth, int frameHeight)
            throws Exception;

    View createItemView(ViewGroup parent, boolean scrollMode);

    void bindItemView(View view, int page, int itemHeightPx, boolean scrollMode);

    void setTheme(ReaderTheme theme);

    boolean needsRelayout(ReaderPrefs prefs);

    /** Re-paginates for new typography and returns the page holding the old position. */
    int relayout(ReaderPrefs prefs, int frameWidth, int frameHeight, int currentPage,
                 float currentFraction);

    /**
     * Holds re-layouts to one spot while the reader previews settings, so a run of changes
     * cannot walk the position backwards page by page. Formats that do not reflow ignore it.
     */
    default void pinAnchor(int page, float fraction, int frameHeight) {
    }

    default void clearAnchor() {
    }

    /** The reader moved: any spot held for re-layouts must be forgotten. */
    default void noteNavigation() {
    }

    void persistPosition(int page, float fraction, int frameHeight);

    // bookmarks
    boolean isBookmarked(int page);

    void toggleBookmark(int page);

    List<Bookmark> bookmarks();

    String bookmarkTitle(int page);

    int pageOfBookmark(Bookmark bookmark);

    void deleteBookmark(Bookmark bookmark);

    // contents (EPUB only, empty otherwise)
    List<EpubBook.TocEntry> contents();

    int pageOfContents(int index);

    /** Releases transient caches under system memory pressure without closing the book. */
    default void trimMemory(int level) {
    }

    void close();
}
