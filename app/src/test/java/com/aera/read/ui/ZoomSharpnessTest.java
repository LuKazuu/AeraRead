package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestPdfSource;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.PagedBook;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.PdfSession;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Magnifying a page has to fetch sharper pixels, not stretch what is already there: an
 * illustration is re-decoded and a PDF page is re-rendered at the size it is shown at.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class ZoomSharpnessTest {

    private PdfSession session;

    @After
    public void tearDown() {
        if (session != null) session.close();
        PdfSession.sourceFactory = PdfRendererSource::open;
    }

    /** A source that records every width it is asked to render at. */
    private static final class RecordingSource extends TestPdfSource {
        final List<Integer> widths = Collections.synchronizedList(new ArrayList<>());

        RecordingSource(int pages) {
            super(pages);
        }

        @Override
        public Bitmap render(int page, int targetWidth) {
            widths.add(targetWidth);
            return super.render(page, targetWidth);
        }
    }

    @Test
    public void anIllustrationIsDecodedWiderWhenThePageIsZoomed() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        EpubBook.Block block = new EpubBook.Block();
        block.imgW = 1200;
        block.imgH = 800;
        block.imagePath = "art.png";
        PagedBook.Segment art = new PagedBook.Segment();
        art.block = block;
        art.height = 480;
        assertTrue(art.isImage());

        PageView view = new PageView(app, true);
        view.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1694, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 822, 1694);

        int plain = view.bitmapWidthFor(art, 822);
        assertTrue("at rest the bitmap matches the drawn width", plain >= 720 && plain <= 822);

        pinch(view);
        assertTrue("zoomed in before measuring", view.isZoomed());
        int zoomed = view.bitmapWidthFor(art, 822);
        assertTrue("a magnified illustration asks for a finer bitmap, " + plain + " -> "
                + zoomed, zoomed > plain);
        assertTrue("and stays within the memory cap", zoomed <= 2048);
    }

    @Test
    public void aMaginfiedPdfPageIsRenderedAgainAtThatSize() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book book = TestSupport.installFakePdf(app, "Zoom (PDF)");
        RecordingSource source = new RecordingSource(12);
        PdfSession.sourceFactory = (context, b) -> source;
        session = PdfSession.open(app, book);
        session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);

        FrameLayout parent = new FrameLayout(app);
        View item = session.createItemView(parent, false);
        ZoomImageView image = (ZoomImageView) item;
        image.layout(0, 0, 822, 1700);
        session.bindItemView(item, 3, 1782, false);
        TestSupport.waitUntil(image::hasBitmap, 8000, "first render");
        int base = source.widths.get(0);

        // pinch the page open
        pinch(image);
        TestSupport.waitUntil(() -> source.widths.stream().anyMatch(w -> w > base), 8000,
                "a sharper render was requested");
        int sharper = source.widths.stream().mapToInt(Integer::intValue).max().orElse(base);
        assertTrue("PDF re-rendered wider than the screen render, " + base + " -> " + sharper,
                sharper > base);
    }

    @Test
    public void aScrollModePdfPageSharpensWhenPinched() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book book = TestSupport.installFakePdf(app, "Zoom (PDF)");
        RecordingSource source = new RecordingSource(12);
        PdfSession.sourceFactory = (context, b) -> source;
        session = PdfSession.open(app, book);
        session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);

        FrameLayout parent = new FrameLayout(app);
        View item = session.createItemView(parent, true);
        ZoomImageView image = (ZoomImageView) item;
        image.layout(0, 0, 822, 1700);
        session.bindItemView(item, 3, 1782, true);
        TestSupport.waitUntil(image::hasBitmap, 8000, "first render");
        int base = source.widths.get(0);

        pinch(image);
        TestSupport.waitUntil(() -> source.widths.stream().anyMatch(w -> w > base), 8000,
                "scroll mode asks for a sharper render too");
    }

    @Test
    public void rebindingTheSamePageLeavesTheZoomAlone() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book book = TestSupport.installFakePdf(app, "Zoom (PDF)");
        PdfSession.sourceFactory = (context, b) -> new RecordingSource(12);
        session = PdfSession.open(app, book);
        session.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);

        FrameLayout parent = new FrameLayout(app);
        View item = session.createItemView(parent, false);
        ZoomImageView image = (ZoomImageView) item;
        image.layout(0, 0, 822, 1700);
        session.bindItemView(item, 3, 1782, false);
        TestSupport.waitUntil(image::hasBitmap, 8000, "first render");
        pinch(image);
        assertTrue(image.isZoomed());
        float[] before = image.transform();

        // a pager rebinds the visible page after every gesture it handles
        session.bindItemView(item, 3, 1782, false);
        float[] after = image.transform();
        assertEquals("zoom survives the rebind", before[0], after[0], 0.001f);
        assertEquals("and so does the place on the page", before[1], after[1], 0.5f);
        assertEquals(before[2], after[2], 0.5f);
    }

    /** Same gesture walk the page tests use: Robolectric drops the first move. */
    private static void pinch(View view) {
        view.onTouchEvent(twoPointer(android.view.MotionEvent.ACTION_DOWN, 380, 800, 440, 800));
        view.onTouchEvent(twoPointer(android.view.MotionEvent.ACTION_POINTER_DOWN
                | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                380, 800, 440, 800));
        view.onTouchEvent(twoPointer(android.view.MotionEvent.ACTION_MOVE, 100, 800, 720, 800));
        view.onTouchEvent(twoPointer(android.view.MotionEvent.ACTION_MOVE, 20, 800, 800, 800));
        view.onTouchEvent(twoPointer(android.view.MotionEvent.ACTION_POINTER_UP, 20, 800, 800, 800));
        long now = android.os.SystemClock.uptimeMillis();
        view.onTouchEvent(android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_UP, 800, 800, 0));
    }

    private static android.view.MotionEvent twoPointer(int action, float x0, float y0,
                                                       float x1, float y1) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties p0 = new android.view.MotionEvent.PointerProperties();
        p0.id = 0;
        android.view.MotionEvent.PointerProperties p1 = new android.view.MotionEvent.PointerProperties();
        p1.id = 1;
        android.view.MotionEvent.PointerCoords c0 = new android.view.MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        android.view.MotionEvent.PointerCoords c1 = new android.view.MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        return android.view.MotionEvent.obtain(now, now, action, 2,
                new android.view.MotionEvent.PointerProperties[]{p0, p1},
                new android.view.MotionEvent.PointerCoords[]{c0, c1}, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
}
