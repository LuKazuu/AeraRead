package com.aera.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.BookFiles;
import com.aera.read.io.CachingSource;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.EpubSession;
import com.aera.read.reader.PdfSession;
import com.aera.read.ui.MainActivity;
import com.aera.read.ui.PageView;
import com.aera.read.ui.ReaderActivity;
import com.aera.read.ui.ZoomImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies the performance, memory footprint, thread-safety and crash-resilience invariants
 * across rendering, pagination, caching, I/O and Activity lifecycles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PerformanceAndStabilityTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
    }

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
        TestSupport.clearLibrary(app);
    }

    @Test
    public void concurrentPdfSessionRenderAndCloseNeverLeaksOrCrashes() throws Exception {
        Book book = TestSupport.installFakePdf(app, "Concurrent Close PDF");
        PdfSession.sourceFactory = (Context c, Book b) -> new TestPdfSource(20) {
            @Override
            public Bitmap render(int page, int targetWidth) {
                try {
                    Thread.sleep(6);
                } catch (InterruptedException ignored) {
                }
                return super.render(page, targetWidth);
            }
        };

        for (int round = 0; round < 15; round++) {
            PdfSession session = PdfSession.open(app, book);
            session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);
            FrameLayout parent = new FrameLayout(app);
            ZoomImageView view = (ZoomImageView) session.createItemView(parent, false);
            view.layout(0, 0, 822, 1700);
            session.bindItemView(view, round % 20, 1782, false);
            session.close();
        }
        Thread.sleep(40);
        TestSupport.idle();
    }

    @Test
    public void pageViewDrawsAllThemesDirectlyWithoutOffscreenSaveLayer() {
        EpubBook book = new EpubBook();
        book.title = "Direct Canvas";
        EpubBook.Chapter ch = new EpubBook.Chapter();
        ch.index = 0;
        ch.path = "ch1.xhtml";
        ch.blocks.add(EpubBook.Block.text("Chapter One", EpubBook.Block.STYLE_H1));
        ch.blocks.add(EpubBook.Block.text(
                "Crisp hardware-drawn text across Paper, Sepia and Night with zero offscreen "
                        + "saveLayer render targets.",
                EpubBook.Block.STYLE_BODY));
        ch.blocks.add(EpubBook.Block.text("First bullet item", EpubBook.Block.STYLE_LIST));
        ch.blocks.add(EpubBook.Block.text("A quoted passage.", EpubBook.Block.STYLE_QUOTE));
        ch.charCount = 240;
        book.chapters.add(ch);

        ReaderPrefs prefs = new ReaderPrefs(app);
        TextStyles styles = TextStyles.create(app, prefs);
        PagedBook paged = EpubPaginator.paginate(book, styles, 720, 1400);

        // Verify all paragraphs share the 4 TextStyles TextPaints rather than cloning native Paints.
        Set<TextPaint> distinctPaints = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PagedBook.Segment seg : paged.page(0).segments) {
            if (!seg.isImage()) distinctPaints.add(seg.layout.getPaint());
        }
        assertTrue("layouts reuse shared style paints: " + distinctPaints.size(),
                distinctPaints.size() <= 4);

        PageView view = new PageView(app, false);
        view.setPadding(40, 40, 40, 40);
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1480, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 800, 1480);

        for (ReaderTheme theme : ReaderTheme.values()) {
            Bitmap surface = Bitmap.createBitmap(800, 1480, Bitmap.Config.ARGB_8888);
            surface.eraseColor(theme.bg);
            Canvas strictHardwareLikeCanvas = new Canvas(surface) {
                @Override
                public int saveLayer(RectF bounds, Paint paint) {
                    throw new AssertionError("saveLayer forbidden on hot page draw path");
                }

                @Override
                public int saveLayer(float left, float top, float right, float bottom,
                                     Paint paint) {
                    throw new AssertionError("saveLayer forbidden on hot page draw path");
                }
            };
            view.setData(paged, paged.page(0), theme, null);
            view.draw(strictHardwareLikeCanvas);

            int inkPixels = 0;
            for (int y = 40; y < 500; y += 2) {
                for (int x = 40; x < 760; x += 2) {
                    if (surface.getPixel(x, y) != theme.bg) inkPixels++;
                }
            }
            assertTrue("theme " + theme.key + " drew visible foreground glyphs", inkPixels > 80);
            surface.recycle();
        }
    }

    @Test
    public void epubSessionReleasesRawCoverBytesImmediatelyOnOpen() throws Exception {
        File epubFile = TestSupport.writeEpubWithCover(app, "Heavy Cover", 800, 1200,
                0, 0, 800, 1200, Color.rgb(40, 90, 150));
        Book book = new Book();
        book.uri = "file://" + epubFile.getAbsolutePath();
        book.title = "Heavy Cover";
        book.type = Book.TYPE_EPUB;

        try (EpubSession session = EpubSession.open(app, book)) {
            Field epubField = EpubSession.class.getDeclaredField("epub");
            epubField.setAccessible(true);
            EpubBook parsed = (EpubBook) epubField.get(session);
            assertNotNull(parsed);
            assertNull("raw cover byte[] released once session opens", parsed.cover);
        }
    }

    @Test
    public void pagedBookBinarySearchHandlesHundredsOfChaptersAccurately() {
        PagedBook paged = new PagedBook();
        int global = 0;
        for (int c = 0; c < 300; c++) {
            PagedBook.ChapterPages cp = new PagedBook.ChapterPages();
            cp.firstGlobal = global;
            int count = (c % 5) + 1;
            for (int p = 0; p < count; p++) {
                PagedBook.Page page = new PagedBook.Page();
                page.chapter = c;
                page.chapterPage = p;
                page.global = global++;
                cp.pages.add(page);
            }
            paged.chapters.add(cp);
        }
        paged.totalPages = global;
        paged.setChapterStarts();

        for (int g = 0; g < paged.totalPages; g++) {
            PagedBook.Page resolved = paged.page(g);
            assertEquals(g, resolved.global);
        }
        assertEquals(0, paged.page(-5).global);
        assertEquals(paged.totalPages - 1, paged.page(paged.totalPages + 50).global);
    }

    @Test
    public void pdfSessionCoalescesDuplicateRendersAndSkipsStalePages() throws Exception {
        Book book = TestSupport.installFakePdf(app, "Fast Flick PDF");
        AtomicInteger renders = new AtomicInteger();
        PdfSession.sourceFactory = (Context c, Book b) -> new TestPdfSource(60) {
            @Override
            public Bitmap render(int page, int targetWidth) {
                renders.incrementAndGet();
                try {
                    Thread.sleep(18);
                } catch (InterruptedException ignored) {
                }
                return super.render(page, targetWidth);
            }
        };

        PdfSession session = PdfSession.open(app, book);
        try {
            session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);
            FrameLayout parent = new FrameLayout(app);
            ZoomImageView singleRecycledCard = (ZoomImageView) session.createItemView(parent, false);
            singleRecycledCard.layout(0, 0, 822, 1700);

            // Simulate scrubbing quickly across 40 pages on recycled ViewHolders and landing on 40.
            for (int p = 0; p <= 40; p++) {
                session.bindItemView(singleRecycledCard, p, 1782, false);
            }
            TestSupport.waitUntil(() -> {
                TestSupport.idle();
                return singleRecycledCard.hasBitmap();
            }, 8000, "landing PDF page rendered");

            int count = renders.get();
            assertTrue("stale off-screen PDF renders skipped (" + count + " << 41)", count < 15);
            session.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        } finally {
            session.close();
        }
    }

    @Test
    public void concurrentEpubImageDecodeAndCloseProducesZeroErrors() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        for (int round = 0; round < 12; round++) {
            EpubSession session = EpubSession.open(app, book);
            session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);
            FrameLayout parent = new FrameLayout(app);
            View item = session.createItemView(parent, false);
            item.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY));
            item.layout(0, 0, 822, 1782);
            Bitmap bmp = Bitmap.createBitmap(822, 1782, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            for (int p = 0; p < Math.min(8, session.pageCount()); p++) {
                session.bindItemView(item, p, 1782, false);
                item.draw(c);
            }
            session.close();
            bmp.recycle();
        }
        Thread.sleep(60);
        TestSupport.idle();
    }

    @Test
    public void libraryCacheAcceleratesReadsAndInvalidatesAutomatically() {
        for (int i = 0; i < 40; i++) {
            Book b = new Book();
            b.uri = "content://library/book_" + i + ".epub";
            b.title = "Book " + i;
            b.addedAt = 1000L + i;
            List<Book> cur = Library.books(app);
            cur.add(b);
            Library.saveBooks(app, cur);
        }
        Book first = Library.books(app).get(0);
        Library.addBookmark(app, first.id(), new Bookmark("Ch 1", 0, 42, 12345L));

        long t0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            assertEquals(40, Library.books(app).size());
            assertEquals(1, Library.bookmarks(app, first.id()).size());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("2000 cached library+bookmark reads complete well under 1s: " + elapsedMs + "ms",
                elapsedMs < 1000);

        // External preference wipe immediately invalidates in-memory cache with zero stale state.
        TestSupport.clearLibrary(app);
        assertEquals(0, Library.books(app).size());
        assertEquals(0, Library.bookmarks(app, first.id()).size());
    }

    @Test
    public void cachingSourceLeavesZeroOrphanedTempFilesOnStreamFailure() {
        File cacheDir = new File(app.getCacheDir(), "atomic_open_test");
        InputStream failing = new InputStream() {
            int remaining = 90_000;

            @Override
            public int read() throws IOException {
                if (remaining-- <= 0) throw new IOException("pipe broken");
                return 'x';
            }
        };
        try {
            new CachingSource(failing, cacheDir);
        } catch (IOException expected) {
            // expected
        }
        File[] leftovers = cacheDir.listFiles();
        assertEquals("zero orphaned .tmp files on mid-stream failure",
                0, leftovers == null ? 0 : leftovers.length);
    }

    @Test
    public void malformedUrisTrimMemoryAndImmediateReaderCloseNeverCrash() throws Exception {
        try {
            BookFiles.openSeekable(app, "file:");
        } catch (IOException expected) {
            assertSame(java.io.FileNotFoundException.class, expected.getClass());
        }

        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<MainActivity> mainScenario =
                     ActivityScenario.launch(MainActivity.class)) {
            MainActivity main = TestSupport.currentActivity(mainScenario);
            main.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
            main.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        }

        // Open ReaderActivity and immediately close before background loading even finishes.
        try (ActivityScenario<ReaderActivity> readerScenario = ActivityScenario.launch(
                new android.content.Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id()))) {
            ReaderActivity reader = TestSupport.currentActivity(readerScenario);
            reader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            reader.finish();
        }
        Thread.sleep(60);
        TestSupport.idle();
    }
}
