package com.aera.read.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.ui.PageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class EpubSessionTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2100;

    private Application app;
    private Book book;
    private ReaderPrefs prefs;
    private EpubSession session;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        book = TestSupport.install(app, "meditations.epub");
        prefs = new ReaderPrefs(app);
        session = EpubSession.open(app, book);
        session.layout(prefs, ReaderTheme.PAPER, WIDTH, HEIGHT);
    }

    @After
    public void tearDown() {
        if (session != null) session.close();
    }

    @Test
    public void opensWithMetadataAndPages() {
        assertEquals("Meditations", session.title());
        assertTrue(session.pageCount() > 10);
        assertTrue(session.pageLabel(4).contains("5"));
    }

    @Test
    public void createsAndBindsPageViews() {
        FrameLayout parent = new FrameLayout(app);
        View view = session.createItemView(parent, false);
        assertTrue(view instanceof PageView);
        session.bindItemView(view, 2, HEIGHT, false);
        assertEquals(0, view.getVisibility());
        session.bindItemView(view, -5, HEIGHT, false);
        assertNotNull(view);
    }

    @Test
    public void pagesAdvanceThroughTheBook() {
        int total = session.pageCount();
        assertTrue(total > 10);
        String first = session.pageLabel(0);
        String last = session.pageLabel(total - 1);
        assertFalse(first.equals(last));
        assertNotNull(session.percentLabel(total / 2));
    }

    @Test
    public void contentsPointAtRealPages() {
        assertTrue(session.supportsContents());
        List<EpubBook.TocEntry> toc = session.contents();
        assertFalse(toc.isEmpty());
        int previous = -1;
        for (int i = 0; i < toc.size(); i++) {
            int page = session.pageOfContents(i);
            assertTrue(page >= 0 && page < session.pageCount());
            assertTrue("contents are ordered", page >= previous);
            previous = page;
        }
    }

    @Test
    public void bookmarkLabelsOnlyUseNamesTheContentsLists() {
        java.util.Set<String> tocTitles = new java.util.HashSet<>();
        for (EpubBook.TocEntry entry : session.contents()) tocTitles.add(entry.title);
        for (int page = 0; page < Math.min(60, session.pageCount()); page += 7) {
            String label = session.bookmarkTitle(page);
            assertFalse("no invented chapter names: " + label, label.contains("Section "));
            String name = label.substring(0, label.lastIndexOf(" \u00b7 "));
            assertTrue("label is either a contents entry or the page number: " + label,
                    tocTitles.contains(name) || name.equals("Page " + (page + 1)));
        }
    }

    @Test
    public void bookmarkRoundTrip() {
        int page = Math.min(3, session.pageCount() - 1);
        assertFalse(session.isBookmarked(page));
        session.toggleBookmark(page);
        assertTrue(session.isBookmarked(page));

        List<Bookmark> marks = session.bookmarks();
        assertEquals(1, marks.size());
        assertFalse(marks.get(0).label.isEmpty());
        assertEquals(page, session.pageOfBookmark(marks.get(0)));
        assertTrue(session.bookmarkTitle(page).contains("5%")
                || session.bookmarkTitle(page).length() > 0);

        session.deleteBookmark(marks.get(0));
        assertFalse(session.isBookmarked(page));
        assertTrue(session.bookmarks().isEmpty());
    }

    @Test
    public void resumeKeepsExactOffsetAcrossSameLayout() {
        int page = session.pageCount() / 2;
        session.persistPosition(page, 0f, HEIGHT);
        Book stored = Library.byId(app, book.id());
        assertNotNull(stored);
        assertEquals(page, session.initialPage());
        assertFalse(stored.layoutKey.isEmpty());
        assertTrue(stored.progress > 0.3f && stored.progress < 0.8f);
    }

    @Test
    public void resumeStoresFractionForMidPagePositions() {
        int page = Math.min(5, session.pageCount() - 1);
        session.persistPosition(page, 0.4f, HEIGHT);
        Book stored = Library.byId(app, book.id());
        assertNotNull(stored);
        assertEquals(0.4f, stored.fraction, 0.02f);
        assertEquals(page, session.initialPage());
        assertTrue(session.initialFraction() > 0.2f);
    }

    @Test
    public void relayoutKeepsReadingPosition() {
        int page = session.pageCount() / 3;
        int before = session.pageCount();
        ReaderPrefs bigger = new ReaderPrefs(app);
        bigger.sizeSp = 26;
        bigger.linePct = 175;
        assertTrue(session.needsRelayout(bigger));
        int mapped = session.relayout(bigger, WIDTH, HEIGHT, page, 0f);
        assertTrue(mapped >= 0 && mapped < session.pageCount());
        assertTrue("larger text means more pages", session.pageCount() > before);
        float expected = page / (float) before;
        float actual = mapped / (float) session.pageCount();
        assertTrue("position roughly preserved", Math.abs(expected - actual) < 0.05f);
    }

    @Test
    public void resumeLandsOnTheSameTextAfterTypographyChanges() throws Exception {
        int page = Math.min(9, session.pageCount() - 1);
        session.persistPosition(page, 0.35f, HEIGHT);
        Book stored = Library.byId(app, book.id());
        assertNotNull(stored);
        int savedChapter = stored.chapter;
        int savedOffset = stored.offset;

        ReaderPrefs bigger = new ReaderPrefs(app);
        bigger.sizeSp = 25;
        bigger.linePct = 168;
        EpubSession reopened = EpubSession.open(app, book);
        try {
            reopened.layout(bigger, ReaderTheme.PAPER, WIDTH, HEIGHT);
            int resumed = reopened.initialPage();
            com.aera.read.epub.PagedBook.Page p = reopened.paged().page(resumed);
            assertEquals("same chapter", savedChapter, p.chapter);
            boolean contains = p.startOffset <= savedOffset
                    && savedOffset < Math.max(p.endOffset, p.startOffset + 1);
            // near the bottom of a page the reader may start on the next one, as long as
            // the skipped line is a couple of lines away at most
            boolean justAfter = p.startOffset >= savedOffset && p.startOffset - savedOffset <= 120;
            assertTrue("resume lands on the saved text", contains || justAfter);
        } finally {
            reopened.close();
        }
    }

    @Test
    public void scrollModeUsesFullHeightItems() {
        assertTrue(session.supportsViewMode());
        FrameLayout parent = new FrameLayout(app);
        View paged = session.createItemView(parent, false);
        View scrolled = session.createItemView(parent, true);
        assertTrue(paged instanceof PageView);
        assertTrue(scrolled instanceof PageView);
        assertNull(scrolled.getLayoutParams());
    }
}
