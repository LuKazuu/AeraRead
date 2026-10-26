package com.aera.read.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestPdfSource;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.ui.ZoomImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PdfSessionTest {

    private Application app;
    private Book book;
    private PdfSession session;
    private TestPdfSource source;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        book = TestSupport.installFakePdf(app, "Gatsby (PDF)");
        source = new TestPdfSource(24);
        PdfSession.sourceFactory = (context, b) -> source;
        session = PdfSession.open(app, book);
        session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);
    }

    @After
    public void tearDown() {
        session.close();
        PdfSession.sourceFactory = PdfRendererSource::open;
    }

    @Test
    public void exposesPagesAndCapabilities() {
        assertEquals("Gatsby (PDF)", session.title());
        assertEquals(24, session.pageCount());
        assertTrue(session.pageLabel(2).contains("3"));
        assertFalse("PDFs have no text typography", session.supportsTypography());
        assertFalse("PDFs have no table of contents", session.supportsContents());
        assertTrue("PDFs support both paged and scroll view modes", session.supportsViewMode());
    }

    @Test
    public void rendersPagesIntoViews() {
        FrameLayout parent = new FrameLayout(app);
        View view = session.createItemView(parent, false);
        assertTrue(view instanceof ZoomImageView);
        session.bindItemView(view, 3, 1782, false);
        TestSupport.waitUntil(() -> ((ZoomImageView) view).hasBitmap(), 3000,
                "page bitmap should arrive");
        assertTrue(source.renders.get() >= 1);
    }

    @Test
    public void bookmarksUsePageNumbers() {
        session.toggleBookmark(9);
        assertTrue(session.isBookmarked(9));
        List<Bookmark> marks = session.bookmarks();
        assertEquals(1, marks.size());
        assertEquals(-1, marks.get(0).chapter);
        assertEquals(9, session.pageOfBookmark(marks.get(0)));
        session.toggleBookmark(9);
        assertFalse(session.isBookmarked(9));
    }

    @Test
    public void positionPersistsAsPageIndex() {
        session.persistPosition(11, 0f, 1782);
        Book stored = Library.byId(app, book.id());
        assertNotNull(stored);
        assertEquals(-1, stored.chapter);
        assertEquals(11, stored.offset);
        assertEquals(0f, stored.fraction, 0.001f);
        assertTrue(stored.progress > 0.4f && stored.progress < 0.55f);

        session.persistPosition(11, 0.45f, 1782);
        Book scrolled = Library.byId(app, book.id());
        assertNotNull(scrolled);
        assertEquals(0.45f, scrolled.fraction, 0.001f);
        assertEquals(11, session.initialPage());
        assertEquals(0.45f, session.initialFraction(), 0.001f);
    }

    @Test
    public void typographyChangesNeverReflowPdfs() {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.sizeSp = 28;
        prefs.font = "inter";
        assertFalse(session.needsRelayout(prefs));
        assertEquals(11, session.relayout(prefs, 822, 1782, 11, 0f));
    }
}
