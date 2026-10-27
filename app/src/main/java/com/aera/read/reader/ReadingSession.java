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

    /** Scroll vs paged reading and LTR/RTL page turn direction. */
    default boolean supportsViewMode() {
        return true;
    }

    int initialPage();

    /** 0..1 offset inside the first visible page (scroll mode resume). */
    float initialFraction();

    /** Exact scroll-item height in pixels for restoring sub-page scroll offsets. */
    default int scrollPageHeight(int page, int frameWidth, int frameHeight) {
        return frameHeight;
    }

    void layout(ReaderPrefs prefs, ReaderTheme theme, int frameWidth, int frameHeight)
            throws Exception;

    View createItemView(ViewGroup parent, boolean scrollMode);

    void bindItemView(View view, int page, int itemHeightPx, boolean scrollMode);

    void setTheme(ReaderTheme theme);

    /** PDF only: whether themes transform the full page or only the surrounding background. */
    default void setPdfFullTheme(boolean fullPage) {
    }

    boolean needsRelayout(ReaderPrefs prefs);

    /** Re-paginates for new typography and returns the page holding the old position. */
    int relayout(ReaderPrefs prefs, int frameWidth, int frameHeight, int currentPage,
                 float currentFraction);

    default void pinAnchor(int page, float fraction, int frameHeight) {
    }

    default void clearAnchor() {
    }

    default void noteNavigation() {
    }

    void persistPosition(int page, float fraction, int frameHeight);

    boolean isBookmarked(int page);

    void toggleBookmark(int page);

    default void toggleBookmark(int page, float fraction) {
        toggleBookmark(page);
    }

    List<Bookmark> bookmarks();

    String bookmarkTitle(int page);

    int pageOfBookmark(Bookmark bookmark);

    default float fractionOfBookmark(Bookmark bookmark) {
        return bookmark == null ? 0f : Math.max(0f, Math.min(0.999f, bookmark.fraction));
    }

    void deleteBookmark(Bookmark bookmark);

    List<EpubBook.TocEntry> contents();

    int pageOfContents(int index);

    default void trimMemory(int level) {
    }

    void close();
}
