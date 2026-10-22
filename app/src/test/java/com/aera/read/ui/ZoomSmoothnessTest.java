package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.ImageStore;
import com.aera.read.epub.PagedBook;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/** Magnifying must never decode on the drawing thread, or the pinch stutters. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class ZoomSmoothnessTest {

    private ZipArchive zip;
    private ImageStore images;
    private File book;

    @Before
    public void setUp() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        book = TestSupport.writeEpubWithCover(app, "Sharp", 900, 1200,
                30, 30, 870, 1170, android.graphics.Color.rgb(20, 90, 150));
        zip = ZipArchive.open(new FileSource(book));
        images = new ImageStore(zip);
    }

    @After
    public void tearDown() throws Exception {
        if (zip != null) zip.close();
    }

    /** The illustration path inside the fixture book. */
    private String imagePath() {
        for (String name : zip.names()) {
            String lower = name.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg")) {
                return name;
            }
        }
        throw new IllegalStateException("no image in the fixture");
    }

    @Test
    public void aColdRequestNeverBlocksAndAnswersLater() {
        String path = imagePath();
        long start = System.currentTimeMillis();
        Bitmap direct = images.request(path, 800, null);
        long elapsed = System.currentTimeMillis() - start;

        assertNull("nothing is decoded on the calling thread", direct);
        assertTrue("asking is immediate, took " + elapsed + " ms", elapsed < 50);

        TestSupport.waitUntil(() -> images.best(path, 800) != null, 5000, "decoded");
        assertEquals(800, images.best(path, 800).getWidth());
    }

    @Test
    public void theBestAvailableIsHandedBackWithoutWaiting() {
        String path = imagePath();
        images.request(path, 400, null);
        TestSupport.waitUntil(() -> images.best(path, 400) != null, 5000, "small copy");

        // a bigger request answers at once with the smaller copy already in memory
        long start = System.currentTimeMillis();
        Bitmap now = images.request(path, 800, null);
        long elapsed = System.currentTimeMillis() - start;
        assertNotNull("something is drawn immediately", now);
        assertTrue("no waiting, took " + elapsed + " ms", elapsed < 50);
        assertEquals(400, now.getWidth());

        TestSupport.waitUntil(() -> images.best(path, 800) != null
                && images.best(path, 800).getWidth() >= 800, 5000, "sharp copy");
        assertEquals(800, images.best(path, 800).getWidth());
    }

    @Test
    public void askingForMoreThanTheSourceHoldsDoesNotUpscaleIt() {
        String path = imagePath();
        Bitmap big = images.request(path, 4000, null);
        assertNull("nothing cached yet", big);
        TestSupport.waitUntil(() -> images.best(path, 4000) != null, 5000, "decoded");
        int width = images.best(path, 4000).getWidth();
        assertTrue("capped at the source, was " + width, width <= 900 && width >= 800);
    }

    @Test
    public void aRepeatRequestForTheSameWidthIsNotQueuedTwice() {
        String path = imagePath();
        images.request(path, 600, null);
        images.request(path, 600, null);
        images.request(path, 600, null);
        TestSupport.waitUntil(() -> images.best(path, 600) != null, 5000, "decoded");
        assertEquals(600, images.best(path, 600).getWidth());
    }

    @Test
    public void drawingAZoomedPageDoesNotDecode() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        // one page that is nothing but an illustration
        EpubBook.Block block = new EpubBook.Block();
        block.imgW = 900;
        block.imgH = 1200;
        block.imagePath = imagePath();
        PagedBook.Segment art = new PagedBook.Segment();
        art.block = block;
        art.height = 1100;
        PagedBook.Page page = new PagedBook.Page();
        page.segments.add(art);
        PagedBook.ChapterPages chapter = new PagedBook.ChapterPages();
        chapter.chapter = new EpubBook.Chapter();
        chapter.pages.add(page);
        PagedBook paged = new PagedBook();
        paged.chapters.add(chapter);
        paged.totalPages = 1;
        paged.setChapterStarts();
        paged.styles = com.aera.read.epub.TextStyles.create(app, new ReaderPrefs(app));

        PageView view = new PageView(app, true);
        view.setData(paged, page, ReaderTheme.PAPER, images);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(822,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1400,
                        android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 822, 1400);
        Bitmap frame = Bitmap.createBitmap(822, 1400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);

        // several frames, then a magnified frame: none of them may stall on a decode
        for (int i = 0; i < 4; i++) {
            long start = System.currentTimeMillis();
            view.draw(canvas);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("frame " + i + " drew in " + elapsed + " ms", elapsed < 250);
        }
    }
}
