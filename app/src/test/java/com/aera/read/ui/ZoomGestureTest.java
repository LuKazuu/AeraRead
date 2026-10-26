package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The gestures a magnified page must keep: the pager or the scrolling list cannot take a
 * drag away mid-pinch, and lifting a finger cannot shift or reset the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class ZoomGestureTest {

    /** A parent shaped like a pager: it grabs a drag as soon as it is allowed to. */
    private static final class GreedyParent extends FrameLayout {
        boolean disallowed;
        boolean stole;

        GreedyParent(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            disallowed = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) disallowed = false;
            boolean grab = !disallowed && event.getActionMasked() == MotionEvent.ACTION_MOVE;
            if (grab) stole = true;
            return grab;
        }
    }

    private static PageView page(Application app, boolean scrollMode) throws Exception {
        PagedBook paged;
        try (ZipArchive zip = ZipArchive.open(new FileSource(
                TestSupport.resourceFile("meditations.epub")))) {
            EpubBook book = new EpubParser(zip).parse("meditations.epub");
            book.zip = null;
            paged = EpubPaginator.paginate(book, TextStyles.create(app, new ReaderPrefs(app)),
                    700, 1200);
        }
        PageView view = new PageView(app, scrollMode);
        view.setData(paged, paged.page(4), ReaderTheme.LIGHT, null);
        return view;
    }

    private static ZoomImageView pdfPage(Application app, boolean scrollMode) {
        ZoomImageView view = new ZoomImageView(app, scrollMode);
        view.show(3, Bitmap.createBitmap(600, 780, Bitmap.Config.ARGB_8888));
        return view;
    }

    private static GreedyParent host(Application app, View child) {
        GreedyParent parent = new GreedyParent(app);
        parent.addView(child, new FrameLayout.LayoutParams(822, 1700));
        parent.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1700, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 822, 1700);
        return parent;
    }

    @Test
    public void aPinchIsNotTakenAwayByThePager() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = page(app, false);
        GreedyParent parent = host(app, view);

        send(parent, MotionEvent.ACTION_DOWN, 410, 800);
        send(parent, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 380, 800, 440, 800);
        send(parent, MotionEvent.ACTION_MOVE, 100, 800, 720, 800);
        send(parent, MotionEvent.ACTION_MOVE, 20, 800, 800, 800);
        send(parent, MotionEvent.ACTION_POINTER_UP, 20, 800, 800, 800);
        send(parent, MotionEvent.ACTION_UP, 800, 800);

        assertFalse("the pager must not claim a two-finger gesture", parent.stole);
        assertTrue("and the pinch reaches the page", view.isZoomed());
    }

    @Test
    public void panningAMagnifiedPageIsNotTakenAwayByThePager() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = page(app, false);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        assertTrue(view.isZoomed());
        float[] before = view.transform();

        send(parent, MotionEvent.ACTION_DOWN, 400, 900);
        send(parent, MotionEvent.ACTION_MOVE, 300, 700);
        send(parent, MotionEvent.ACTION_UP, 300, 700);

        assertFalse("a drag on a magnified page stays with the page", parent.stole);
        assertTrue("and the page really moved", Math.abs(view.transform()[1] - before[1]) > 80);
    }

    @Test
    public void aPinchZoomsAPdfPageInScrollMode() {
        Application app = ApplicationProvider.getApplicationContext();
        ZoomImageView view = pdfPage(app, true);
        GreedyParent parent = host(app, view);
        assertFalse(view.isZoomed());
        pinch(parent, 60, 760);
        assertTrue("two fingers zoom a PDF page in scroll mode too", view.isZoomed());
    }

    @Test
    public void anEpubPinchZoomsInScrollModeToo() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = page(app, true);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        assertTrue(view.isZoomed());
    }

    @Test
    public void oneFingerScrollsTheListOnAnUnmagnifiedPdfPage() {
        Application app = ApplicationProvider.getApplicationContext();
        ZoomImageView view = pdfPage(app, true);
        GreedyParent parent = host(app, view);
        float[] before = view.transform();

        send(parent, MotionEvent.ACTION_DOWN, 400, 1200);
        send(parent, MotionEvent.ACTION_MOVE, 400, 900);
        send(parent, MotionEvent.ACTION_UP, 400, 900);

        assertTrue("the list keeps its one-finger scroll", parent.stole);
        assertEquals("and the page itself does not move", before[1], view.transform()[1], 0.001f);
    }

    @Test
    public void aHigherResolutionRenderKeepsThePinchedView() {
        Application app = ApplicationProvider.getApplicationContext();
        ZoomImageView view = pdfPage(app, false);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        assertTrue(view.isZoomed());
        float[] before = view.transform();
        float beforeWidth = 600 * before[0];
        float centreX = before[1] + beforeWidth / 2f;

        // the sharp render requested for the zoomed level lands
        view.replaceBitmap(Bitmap.createBitmap(1200, 1560, Bitmap.Config.ARGB_8888));

        float[] after = view.transform();
        float afterWidth = 1200 * after[0];
        assertEquals("same magnification on screen", beforeWidth, afterWidth, 1f);
        assertEquals("same centre point", centreX, after[1] + afterWidth / 2f, 1f);
        assertTrue("still magnified", view.isZoomed());
    }

    @Test
    public void aLateRenderDoesNotUndoAPinch() {
        Application app = ApplicationProvider.getApplicationContext();
        ZoomImageView view = pdfPage(app, false);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        float[] before = view.transform();

        // the first screen-sized render landing after the reader zoomed must be ignored
        view.show(3, Bitmap.createBitmap(600, 780, Bitmap.Config.ARGB_8888));

        float[] after = view.transform();
        assertEquals(before[0], after[0], 0.001f);
        assertEquals(before[1], after[1], 0.001f);
        assertEquals(before[2], after[2], 0.001f);
    }

    @Test
    public void oneFingerScrollsTheListOnAnUnmagnifiedEpubPage() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = page(app, true);
        GreedyParent parent = host(app, view);
        float[] before = view.transform();

        send(parent, MotionEvent.ACTION_DOWN, 400, 1200);
        send(parent, MotionEvent.ACTION_MOVE, 400, 900);
        send(parent, MotionEvent.ACTION_UP, 400, 900);

        assertTrue("an untouched page leaves the scroll to the list", parent.stole);
        assertEquals(before[1], view.transform()[1], 0.001f);
    }

    @Test
    public void aMagnifiedEpubPagePansInsideTheList() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = page(app, true);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        assertTrue(view.isZoomed());
        float[] before = view.transform();

        send(parent, MotionEvent.ACTION_DOWN, 400, 900);
        send(parent, MotionEvent.ACTION_MOVE, 300, 700);
        send(parent, MotionEvent.ACTION_UP, 300, 700);

        assertFalse("the list must not scroll while the page is panned", parent.stole);
        assertTrue(Math.abs(view.transform()[1] - before[1]) > 80);
    }

    @Test
    public void aNewPageStartsFitted() {
        Application app = ApplicationProvider.getApplicationContext();
        ZoomImageView view = pdfPage(app, false);
        GreedyParent parent = host(app, view);
        pinch(parent, 60, 760);
        assertTrue(view.isZoomed());

        view.show(4, Bitmap.createBitmap(600, 780, Bitmap.Config.ARGB_8888));
        assertFalse("turning the page drops the last page's zoom", view.isZoomed());
    }

    /** Two fingers meeting at the middle of the page, spreading, then lifting one. */
    private static void pinch(GreedyParent parent, float startSpan, float endSpan) {
        float mid = 410f;
        send(parent, MotionEvent.ACTION_DOWN, mid, 800);
        send(parent, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                mid - startSpan / 2, 800, mid + startSpan / 2, 800);
        send(parent, MotionEvent.ACTION_MOVE,
                mid - endSpan * 0.4f, 800, mid + endSpan * 0.4f, 800);
        send(parent, MotionEvent.ACTION_MOVE,
                mid - endSpan / 2, 800, mid + endSpan / 2, 800);
        send(parent, MotionEvent.ACTION_POINTER_UP,
                mid - endSpan / 2, 800, mid + endSpan / 2, 800);
        send(parent, MotionEvent.ACTION_UP, mid + endSpan / 2, 800);
    }

    private static void send(FrameLayout parent, int action, float x0, float y0) {
        send(parent, action, x0, y0, 0, 0);
    }

    private static void send(FrameLayout parent, int action, float x0, float y0,
                             float x1, float y1) {
        boolean two = x1 != 0 || y1 != 0;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p0 = new MotionEvent.PointerProperties();
        p0.id = 0;
        MotionEvent.PointerCoords c0 = new MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        if (!two) {
            parent.dispatchTouchEvent(MotionEvent.obtain(now, now, action, 1,
                    new MotionEvent.PointerProperties[]{p0},
                    new MotionEvent.PointerCoords[]{c0}, 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0));
            return;
        }
        MotionEvent.PointerProperties p1 = new MotionEvent.PointerProperties();
        p1.id = 1;
        MotionEvent.PointerCoords c1 = new MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        parent.dispatchTouchEvent(MotionEvent.obtain(now, now, action, 2,
                new MotionEvent.PointerProperties[]{p0, p1},
                new MotionEvent.PointerCoords[]{c0, c1}, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0));
    }
}
