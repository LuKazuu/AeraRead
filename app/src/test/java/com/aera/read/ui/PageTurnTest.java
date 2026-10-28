package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Taps in both Pages and Scroll modes drive continuous additive hardware-VSYNC glides on
 * their backing {@link RecyclerView}s: smooth right-to-left / up-down motion with zero
 * hold-back when fast-tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PageTurnTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.theme = "light";
        prefs.viewMode = "pages";
        prefs.direction = "ltr";
        prefs.font = "system";
        prefs.sizeSp = 18;
        prefs.linePct = 155;
        // Edge tap zones are opt-in since v1.1.2; this suite exercises them explicitly.
        prefs.tapToTurn = true;
        prefs.save();
    }

    @After
    public void tearDown() {
        TestSupport.clearLibrary(app);
    }

    private void tap(ReaderActivity activity, float x) {
        long y = 900;
        activity.runOnUiThread(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0);
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        });
    }

    private static void advanceFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            boolean[] vsyncFired = {false};
            android.view.Choreographer.getInstance().postFrameCallback(ns -> vsyncFired[0] = true);
            while (!vsyncFired[0]) {
                Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
            }
        }
    }

    private ReaderActivity open(Book book, ActivityScenario<ReaderActivity>[] out) {
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        out[0] = scenario;
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 15000, "reader");
        TestSupport.layout(activity);
        TestSupport.idle();
        return activity;
    }

    private static RecyclerView pagerRecycler(ReaderActivity activity) {
        View pager = activity.findViewById(R.id.pager);
        return (RecyclerView) ((ViewGroup) pager).getChildAt(0);
    }

    @Test
    public void pagesModeTapGlidesRealPagesRightToLeftAndSupportsRtl() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(4));
            TestSupport.idle();
            RecyclerView rv = pagerRecycler(activity);
            int w = activity.findViewById(R.id.pager).getWidth();
            assertTrue(w > 400);
            int before = rv.computeHorizontalScrollOffset();

            // Forward tap in L/R glides real ViewPager2 items right-to-left (+dx).
            tap(activity, 780);
            assertEquals(5, activity.getCurrentPage());
            advanceFrames(3);
            int mid = rv.computeHorizontalScrollOffset();
            assertTrue("real pages are genuinely mid-slide right-to-left (mid=" + mid
                    + ", before=" + before + ", w=" + w + ")",
                    mid > before + w * 0.20f && mid < before + w * 0.90f);

            TestSupport.idle();
            assertEquals(before + w, rv.computeHorizontalScrollOffset());
            assertEquals(5, activity.getCurrentPage());
            assertEquals(5, pagerPosition(activity));

            // Switch to R/L: left edge advances forward smoothly in the RTL direction.
            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.direction = "rtl";
            prefs.save();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(false));
            TestSupport.idle();
            View item5 = rv.getLayoutManager().findViewByPosition(5);
            assertNotNull(item5);

            tap(activity, 40);
            assertEquals(6, activity.getCurrentPage());
            advanceFrames(3);
            assertTrue("RTL forward glide moves live item mid-flight", item5.getLeft() > 0);
            TestSupport.idle();
            assertEquals(6, activity.getCurrentPage());
            assertEquals(6, pagerPosition(activity));
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void fourFastTapsInPagesModeAccelerateContinuouslyWithZeroHoldBack() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(4));
            TestSupport.idle();
            RecyclerView rv = pagerRecycler(activity);
            int w = activity.findViewById(R.id.pager).getWidth();
            int start = activity.getCurrentPage();
            int baseOffset = rv.computeHorizontalScrollOffset();
            int prevOffset = baseOffset;

            for (int i = 0; i < 4; i++) {
                tap(activity, 780);
                assertEquals("tap " + (i + 1) + " advances immediately",
                        start + i + 1, activity.getCurrentPage());
                advanceFrames(2);
                int now = rv.computeHorizontalScrollOffset();
                assertTrue("fast tap " + (i + 1) + " never resets or holds back ("
                        + prevOffset + " -> " + now + ")", now > prevOffset);
                prevOffset = now;
            }

            TestSupport.idle();
            assertEquals("settles cleanly at exact 4 * width offset",
                    baseOffset + 4 * w, rv.computeHorizontalScrollOffset());
            assertEquals("no page is replayed afterwards", start + 4, activity.getCurrentPage());
            assertEquals("the pager agrees", start + 4, pagerPosition(activity));
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void fastTapsBackwardsInPagesModeAlsoAccelerateContinuously() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(12));
            TestSupport.idle();
            RecyclerView rv = pagerRecycler(activity);
            int w = activity.findViewById(R.id.pager).getWidth();
            int start = activity.getCurrentPage();
            int baseOffset = rv.computeHorizontalScrollOffset();
            int prevOffset = baseOffset;

            for (int i = 0; i < 5; i++) {
                tap(activity, 40);
                assertEquals(start - i - 1, activity.getCurrentPage());
                advanceFrames(2);
                int now = rv.computeHorizontalScrollOffset();
                assertTrue("backward fast tap " + (i + 1) + " glides smoothly back",
                        now < prevOffset);
                prevOffset = now;
            }
            TestSupport.idle();
            assertEquals(baseOffset - 5 * w, rv.computeHorizontalScrollOffset());
            assertEquals(start - 5, activity.getCurrentPage());
            assertEquals(start - 5, pagerPosition(activity));
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void aTapInTheMiddleStillOnlyTogglesChrome() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(6));
            TestSupport.idle();
            int page = activity.getCurrentPage();
            tap(activity, 411);
            TestSupport.idle();
            assertEquals(page, activity.getCurrentPage());
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void aZoomedPageDoesNotTurnWhenTapped() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(5));
            TestSupport.idle();
            int page = activity.getCurrentPage();

            PageView pageView = currentPageView(activity);
            assertNotNull("the page is on screen", pageView);
            activity.runOnUiThread(() -> pinch(pageView));
            TestSupport.idle();
            assertTrue("the page really is magnified", pageView.isZoomed());

            tap(activity, 780);
            assertEquals("a tap while zoomed resets zoom instead of turning", page,
                    activity.getCurrentPage());
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void tapsStopAtTheFirstAndLastPage() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            int last = activity.getSession().pageCount() - 1;
            activity.runOnUiThread(() -> activity.goToPage(0));
            TestSupport.idle();
            tap(activity, 40);
            TestSupport.idle();
            assertEquals("nothing before the first page", 0, activity.getCurrentPage());

            activity.runOnUiThread(() -> activity.goToPage(last));
            TestSupport.idle();
            for (int i = 0; i < 3; i++) tap(activity, 780);
            TestSupport.idle();
            assertEquals("nothing past the last page", last, activity.getCurrentPage());
            assertEquals(last, pagerPosition(activity));
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void aBookWhoseFontFileIsGoneOpensInTheSystemFace() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.font = "inter";      // set, but never downloaded
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            assertNotNull("the book opens", activity.getSession());
            assertEquals("the stale choice is dropped", "system",
                    new ReaderPrefs(app).font);
            assertTrue(activity.getSession().pageCount() > 0);
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void fastTapsForwardInScrollModeAnimateSmoothlyAndAccumulateEverySingleTap()
            throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            int step = (int) (activity.findViewById(R.id.content_frame).getHeight() * ReaderActivity.TAP_SCROLL_FRACTION);
            assertTrue("non-trivial scroll step", step > 500);

            int before = list.computeVerticalScrollOffset();
            tap(activity, 780);
            advanceFrames(3);
            int mid = list.computeVerticalScrollOffset();
            assertTrue("scroll mode genuinely animates mid-flight (mid=" + mid + ", step="
                    + step + ")", mid > before + step * 0.20f && mid < before + step * 0.90f);
            TestSupport.idle();
            assertEquals("single tap settles to exact pixel step",
                    before + step, list.computeVerticalScrollOffset());

            int base = list.computeVerticalScrollOffset();
            int prev = base;
            for (int i = 0; i < 6; i++) {
                tap(activity, 780);
                advanceFrames(2);
                int now = list.computeVerticalScrollOffset();
                assertTrue("fast tap " + (i + 1) + " keeps moving smoothly forward (" + prev
                        + " -> " + now + ")", now > prev);
                prev = now;
            }
            TestSupport.idle();
            assertEquals("all 6 fast overlapping taps accumulate to exact 6 * step",
                    base + 6 * step, list.computeVerticalScrollOffset());
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void fastTapsBackwardInScrollModeAnimateAndClampCleanlyAtBounds() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            activity.runOnUiThread(() -> activity.goToPage(12));
            TestSupport.idle();
            int step = (int) (activity.findViewById(R.id.content_frame).getHeight() * ReaderActivity.TAP_SCROLL_FRACTION);
            int startOffset = list.computeVerticalScrollOffset();
            assertTrue("started well down the book", startOffset >= 10 * step);

            int prev = startOffset;
            for (int i = 0; i < 6; i++) {
                tap(activity, 40);
                advanceFrames(2);
                int now = list.computeVerticalScrollOffset();
                assertTrue("backward fast tap " + (i + 1) + " glides smoothly upward", now < prev);
                prev = now;
            }
            TestSupport.idle();
            assertEquals(startOffset - 6 * step, list.computeVerticalScrollOffset());

            activity.runOnUiThread(() -> activity.goToPage(0));
            TestSupport.idle();
            for (int i = 0; i < 4; i++) {
                tap(activity, 40);
                advanceFrames(1);
            }
            TestSupport.idle();
            assertEquals("clamps cleanly at top of book", 0, list.computeVerticalScrollOffset());
            tap(activity, 780);
            TestSupport.idle();
            assertEquals("no negative debt left after hitting top wall",
                    step, list.computeVerticalScrollOffset());

            int last = activity.getSession().pageCount() - 1;
            activity.runOnUiThread(() -> activity.goToPage(last));
            TestSupport.idle();
            int maxOffset = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent();
            for (int i = 0; i < 4; i++) {
                tap(activity, 780);
                advanceFrames(1);
            }
            TestSupport.idle();
            assertEquals("clamps cleanly at bottom of book",
                    maxOffset, list.computeVerticalScrollOffset());
            tap(activity, 40);
            TestSupport.idle();
            assertEquals("no positive debt left after hitting bottom wall",
                    maxOffset - step, list.computeVerticalScrollOffset());
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void fastTapsInScrollModeInterruptActiveFlingAndPersistEvenMidAnimation()
            throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            int step = (int) (activity.findViewById(R.id.content_frame).getHeight() * ReaderActivity.TAP_SCROLL_FRACTION);

            activity.runOnUiThread(() -> list.smoothScrollBy(0, 4000));
            int baseline = list.computeVerticalScrollOffset();

            for (int i = 0; i < 8; i++) {
                activity.runOnUiThread(() -> {
                    long now = android.os.SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now,
                            MotionEvent.ACTION_DOWN, 770f, 900f, 0);
                    MotionEvent up = MotionEvent.obtain(now, now + 25,
                            MotionEvent.ACTION_UP, 784f, 914f, 0);
                    activity.dispatchTouchEvent(down);
                    activity.dispatchTouchEvent(up);
                    down.recycle();
                    up.recycle();
                });
                advanceFrames(2);
            }
            int frameH = activity.findViewById(R.id.content_frame).getHeight();
            ref[0].close();
            int expectedPage = (baseline + 8 * step) / frameH;

            ActivityScenario<ReaderActivity>[] ref2 = new ActivityScenario[1];
            try {
                ReaderActivity reopened = open(book, ref2);
                assertEquals("edge-tap position flushed & persisted cleanly even mid-animation",
                        expectedPage, reopened.getCurrentPage());
            } finally {
                ref2[0].close();
            }
            return;
        } finally {
            ref[0].close();
        }
    }

    @Test
    public void manualDragMidAnimationCatchesLivePageWithoutJump() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        ActivityScenario<ReaderActivity>[] ref = new ActivityScenario[1];
        try {
            ReaderActivity activity = open(book, ref);
            activity.runOnUiThread(() -> activity.goToPage(4));
            TestSupport.idle();
            RecyclerView rv = pagerRecycler(activity);

            tap(activity, 780);
            advanceFrames(3);
            int midOffset = rv.computeHorizontalScrollOffset();

            activity.runOnUiThread(() -> {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_DOWN, 600f, 900f, 0);
                MotionEvent move = MotionEvent.obtain(now, now + 16,
                        MotionEvent.ACTION_MOVE, 500f, 900f, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 32,
                        MotionEvent.ACTION_UP, 500f, 900f, 0);
                activity.dispatchTouchEvent(down);
                activity.dispatchTouchEvent(move);
                assertTrue("live page caught smoothly under finger without resetting back",
                        rv.computeHorizontalScrollOffset() >= midOffset);
                activity.dispatchTouchEvent(up);
                down.recycle();
                move.recycle();
                up.recycle();
            });
            TestSupport.idle();
        } finally {
            ref[0].close();
        }
    }

    private static int pagerPosition(ReaderActivity activity) {
        View pager = activity.findViewById(R.id.pager);
        if (!(pager instanceof androidx.viewpager2.widget.ViewPager2)) return -1;
        return ((androidx.viewpager2.widget.ViewPager2) pager).getCurrentItem();
    }

    private static PageView currentPageView(ReaderActivity activity) {
        View found = activity.findViewById(R.id.pager);
        if (!(found instanceof androidx.viewpager2.widget.ViewPager2)) return null;
        androidx.viewpager2.widget.ViewPager2 pager =
                (androidx.viewpager2.widget.ViewPager2) found;
        ViewGroup child = (ViewGroup) pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder holder = ((RecyclerView) child)
                .findViewHolderForAdapterPosition(activity.getCurrentPage());
        View item = holder == null ? null : holder.itemView;
        return item instanceof PageView ? (PageView) item : null;
    }

    private static void pinch(PageView view) {
        view.onTouchEvent(two(MotionEvent.ACTION_DOWN, 380, 800, 440, 800));
        view.onTouchEvent(two(MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 380, 800, 440, 800));
        view.onTouchEvent(two(MotionEvent.ACTION_MOVE, 100, 800, 720, 800));
        view.onTouchEvent(two(MotionEvent.ACTION_MOVE, 20, 800, 800, 800));
        view.onTouchEvent(two(MotionEvent.ACTION_POINTER_UP, 20, 800, 800, 800));
        long now = android.os.SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 800, 800, 0));
    }

    private static MotionEvent two(int action, float x0, float y0, float x1, float y1) {
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
        return MotionEvent.obtain(now, now, action, 2,
                new MotionEvent.PointerProperties[]{p0, p1},
                new MotionEvent.PointerCoords[]{c0, c1}, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
}
