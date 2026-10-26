package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
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
 * Two fingers magnify the text in pages mode, panning follows while magnified, and letting go
 * of the last finger does not turn the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PageZoomTest {

    private EpubBook book() throws Exception {
        try (ZipArchive zip = ZipArchive.open(new FileSource(
                TestSupport.resourceFile("meditations.epub")))) {
            EpubBook b = new EpubParser(zip).parse("meditations.epub");
            b.zip = null;
            return b;
        }
    }

    private PageView view(Application app, boolean scrollMode) throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        PagedBook paged = EpubPaginator.paginate(book(), TextStyles.create(app, prefs), 700, 1200);
        PageView view = new PageView(app, scrollMode);
        view.setData(paged, paged.page(4), com.aera.read.data.ReaderTheme.LIGHT, null);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(822,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1694,
                        android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 822, 1694);
        return view;
    }

    private static Bitmap paint(PageView view) {
        Bitmap bitmap = Bitmap.createBitmap(822, 1694, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /** One finger with an explicit pointer id, as Android reports after a lift. */
    private static MotionEvent single(int id, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** A pointer-up carrying the index of the finger that lifts. */
    private static MotionEvent twoPointerUp(int liftIndex, float x0, float y0, float x1, float y1) {
        return event(MotionEvent.ACTION_POINTER_UP
                | (liftIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x0, y0, x1, y1);
    }

    private static MotionEvent event(int action, float x0, float y0, float x1, float y1) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p0 = new MotionEvent.PointerProperties();
        p0.id = 0;
        MotionEvent.PointerProperties p1 = new MotionEvent.PointerProperties();
        p1.id = 1;
        MotionEvent.PointerCoords c0 = new MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        MotionEvent.PointerCoords c1 = new MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        MotionEvent event = MotionEvent.obtain(now, now, action, 2,
                new MotionEvent.PointerProperties[]{p0, p1},
                new MotionEvent.PointerCoords[]{c0, c1}, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        return event;
    }

    @Test
    public void pinchingMagnifiesTheText() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = view(app, true);
        assertFalse(view.isZoomed());
        Bitmap before = paint(view);
        int inkBefore = ink(before);

        pinchApart(view);

        assertTrue("the page is magnified", view.isZoomed());
        Bitmap after = paint(view);
        assertNotEquals("the text is drawn differently", inkBefore, ink(after));
        assertTrue("glyphs take more room when zoomed", spreadX(after) > spreadX(before));
    }

    @Test
    public void aZoomedPageCanBePanned() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = view(app, true);
        pinchApart(view);
        assertTrue(view.isZoomed());
        Bitmap zoomed = paint(view);

        // one finger drag while zoomed moves the page
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 600, 1200));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 400, 900));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 200, 700));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 200, 700));
        Bitmap panned = paint(view);
        assertNotEquals("panning changes what is on screen", ink(zoomed), ink(panned));
    }

    @Test
    public void scrollModeZoomsWithTwoFingers() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = view(app, true);
        assertFalse(view.isZoomed());
        pinchApart(view);
        assertTrue("a pinch zooms in scroll mode too", view.isZoomed());
    }

    @Test
    public void liftinOneFingerDoesNotJumpThePage() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = view(app, false);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 380, 800, 440, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 380, 800, 440, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100, 800, 720, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20, 800, 800, 800));
        float[] before = view.transform();
        // the first finger lifts while the second stays down, exactly as on a real device
        view.onTouchEvent(twoPointerUp(0, 20, 800, 800, 800));
        float[] afterLift = view.transform();
        assertEquals("zoom is untouched", before[0], afterLift[0], 0.001f);
        assertEquals("no sideways jump", before[1], afterLift[1], 0.5f);
        assertEquals("no vertical jump", before[2], afterLift[2], 0.5f);

        // and the remaining finger still pans by its own movement only; Android keeps
        // its pointer id (1) once the first finger is gone
        view.onTouchEvent(single(1, 780, 800));
        float[] panned = view.transform();
        // dragging the remaining finger 20 px left carries the page 20 px left with it
        assertEquals("a 20 px drag moves the page by 20 px", afterLift[1] - 20f, panned[1], 0.5f);
    }

    @Test
    public void aFreshPageStartsUnzoomed() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PageView view = view(app, true);
        pinchApart(view);
        assertTrue(view.isZoomed());

        PagedBook paged = EpubPaginator.paginate(book(), TextStyles.create(app,
                new ReaderPrefs(app)), 700, 1200);
        view.setData(paged, paged.page(5), com.aera.read.data.ReaderTheme.LIGHT, null);
        assertFalse("turning the page resets the zoom", view.isZoomed());
    }

    /** Two fingers starting 60px apart and spreading to 780px, the way a pinch arrives. */
    private static void pinchApart(PageView view) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 380, 800, 440, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 380, 800, 440, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100, 800, 720, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20, 800, 800, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP, 20, 800, 800, 800));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 800, 800));
    }

    private static int ink(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 3) {
            for (int x = 0; x < bitmap.getWidth(); x += 3) {
                int c = bitmap.getPixel(x, y);
                if (Color.red(c) < 150 && Color.green(c) < 150) count++;
            }
        }
        return count;
    }

    /** Horizontal spread of the inked area: grows when the page is magnified. */
    private static int spreadX(Bitmap bitmap) {
        int min = bitmap.getWidth();
        int max = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 4) {
            for (int x = 0; x < bitmap.getWidth(); x += 4) {
                int c = bitmap.getPixel(x, y);
                if (Color.red(c) < 150 && Color.green(c) < 150) {
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
        }
        return max - min;
    }
}
