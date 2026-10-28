package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.aera.read.TestPdfSource;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.PdfSession;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * v1.1.2 tap-zone contract:
 * 1. Edge taps are opt-in via {@link ReaderPrefs#tapToTurn} and the default is OFF for
 *    both view modes — with the gesture disabled no tap ever turns a page or scrolls,
 *    every content tap toggles the chrome instead.
 * 2. When enabled, a Scroll-mode edge tap advances exactly
 *    {@link ReaderActivity#TAP_SCROLL_FRACTION} (60%) of the viewport — deliberately
 *    shorter than the old 85% jump so 40% of the previous screen stays visible.
 * 3. Pages mode keeps L/R and R/L behaviour, RTL flips the zones, zoom-reset still wins,
 *    toolbar taps never navigate, and the preference persists across sessions and is
 *    toggled live from the Appearance sheet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class TapZonesSettingTest {

    /** The pre-1.1.2 scroll step fraction; new taps must travel strictly less. */
    private static final float OLD_SCROLL_FRACTION = 0.85f;

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
        prefs.save();
    }

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
        TestSupport.clearLibrary(app);
    }

    // ------------------------------------------------------------------ default off

    @Test
    public void tapToTurnDefaultsToOffInFreshPreferences() {
        assertFalse("edge tap zones must be opt-in", new ReaderPrefs(app).tapToTurn);
    }

    @Test
    public void withSettingOffEdgeTapsNeverTurnPagesAndOnlyToggleChrome() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertEquals(0, activity.getCurrentPage());

            tap(activity, 0.92f, 0.5f);
            assertEquals("right edge tap must not advance while the setting is off",
                    0, activity.getCurrentPage());
            assertFalse("an off-setting tap behaves like the old centre tap: chrome hides",
                    activity.isChromeVisible());

            tap(activity, 0.08f, 0.5f);
            assertEquals("left edge tap must not go back either", 0, activity.getCurrentPage());
            assertTrue("and it brings the chrome back", activity.isChromeVisible());

            for (int i = 0; i < 5; i++) tap(activity, 0.75f + (i % 2) * 0.05f, 0.5f);
            assertEquals("rapid edge taps remain inert for navigation",
                    0, activity.getCurrentPage());
        }
    }

    @Test
    public void withSettingOffEdgeTapsNeverScrollTheScrollView() throws Exception {
        setPrefs("scroll", "ltr", false);
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            assertEquals(View.VISIBLE, list.getVisibility());
            int before = list.computeVerticalScrollOffset();

            tap(activity, 0.92f, 0.5f);
            advanceFrames(4);
            TestSupport.idle();
            tap(activity, 0.08f, 0.5f);
            advanceFrames(4);
            TestSupport.idle();

            assertEquals("scroll mode never moves from edge taps while the setting is off",
                    before, list.computeVerticalScrollOffset());
            assertEquals(0, activity.getCurrentPage());
        }
    }

    @Test
    public void withSettingOffAndChromeHiddenAnyContentTapRestoresTheChrome() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.setChromeVisible(false));
            TestSupport.idle();
            assertFalse(activity.isChromeVisible());

            // The reader must never trap the user without a way back to the toolbars.
            tap(activity, 0.92f, 0.5f);
            assertTrue("edge tap restores chrome when navigation taps are disabled",
                    activity.isChromeVisible());
        }
    }

    // ------------------------------------------------------------------ enabled: pages mode

    @Test
    public void withSettingOnPagesModeEdgeTapsTurnPagesLtr() throws Exception {
        setPrefs("pages", "ltr", true);
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertEquals(0, activity.getCurrentPage());

            tap(activity, 0.92f, 0.5f);
            assertEquals("right edge advances in L/R", 1, activity.getCurrentPage());

            tap(activity, 0.08f, 0.5f);
            assertEquals("left edge goes back in L/R", 0, activity.getCurrentPage());

            tap(activity, 0.5f, 0.5f);
            assertFalse("centre still toggles chrome", activity.isChromeVisible());
        }
    }

    @Test
    public void withSettingOnPagesModeRtlFlipsTheZones() throws Exception {
        setPrefs("pages", "rtl", true);
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);

            tap(activity, 0.08f, 0.5f);
            assertEquals("left edge advances forward in R/L", 1, activity.getCurrentPage());

            tap(activity, 0.92f, 0.5f);
            assertEquals("right edge goes back in R/L", 0, activity.getCurrentPage());
        }
    }

    @Test
    public void withSettingOnEdgeTapsClampAtBothEndsOfTheBook() throws Exception {
        setPrefs("pages", "ltr", true);
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);

            for (int i = 0; i < 8; i++) tap(activity, 0.08f, 0.5f);
            TestSupport.idle();
            assertEquals("back taps clamp at the first page", 0, activity.getCurrentPage());

            int last = activity.getSession().pageCount() - 1;
            activity.runOnUiThread(() -> activity.goToPage(last));
            TestSupport.idle();
            for (int i = 0; i < 4; i++) tap(activity, 0.92f, 0.5f);
            TestSupport.idle();
            assertEquals("forward taps clamp at the last page",
                    last, activity.getCurrentPage());
        }
    }

    // ------------------------------------------------------------------ enabled: scroll mode

    @Test
    public void withSettingOnScrollModeTapsAdvanceTheReducedSixtyPercentStep() throws Exception {
        setPrefs("scroll", "ltr", true);
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            int frameH = activity.findViewById(R.id.content_frame).getHeight();
            int step = (int) (frameH * ReaderActivity.TAP_SCROLL_FRACTION);
            int oldStep = (int) (frameH * OLD_SCROLL_FRACTION);

            assertEquals("v1.1.2 reduced the tap step to 60% of the viewport",
                    0.60f, ReaderActivity.TAP_SCROLL_FRACTION, 0.0001f);
            assertTrue("the new step is genuinely shorter than the old 85% jump ("
                            + step + " < " + oldStep + ")",
                    step < oldStep);
            assertTrue("overlap kept for reading context: old screen still 40% visible",
                    frameH - step >= (int) (frameH * 0.40f));

            int before = list.computeVerticalScrollOffset();
            tap(activity, 0.92f, 0.5f);
            TestSupport.idle();
            assertEquals("a forward tap settles on exactly the reduced step",
                    before + step, list.computeVerticalScrollOffset());

            tap(activity, 0.08f, 0.5f);
            TestSupport.idle();
            assertEquals("a backward tap returns exactly one reduced step",
                    before, list.computeVerticalScrollOffset());
        }
    }

    @Test
    public void withSettingOnScrollModeRtlKeepsVerticalDirectionIntuitive() throws Exception {
        // Direction (L/R vs R/L) must not invert vertical scrolling in Scroll mode.
        setPrefs("scroll", "rtl", true);
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            int frameH = activity.findViewById(R.id.content_frame).getHeight();
            int step = (int) (frameH * ReaderActivity.TAP_SCROLL_FRACTION);

            int before = list.computeVerticalScrollOffset();
            tap(activity, 0.92f, 0.5f);
            TestSupport.idle();
            assertEquals("right edge still scrolls down in Scroll mode with R/L set",
                    before + step, list.computeVerticalScrollOffset());
        }
    }

    @Test
    public void withSettingOnScrollModeTapsClampCleanlyAtTheTopWall() throws Exception {
        setPrefs("scroll", "ltr", true);
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            for (int i = 0; i < 5; i++) {
                tap(activity, 0.08f, 0.5f);
                advanceFrames(2);
            }
            TestSupport.idle();
            assertEquals("clamped at the very top", 0, list.computeVerticalScrollOffset());

            tap(activity, 0.92f, 0.5f);
            TestSupport.idle();
            int step = (int) (activity.findViewById(R.id.content_frame).getHeight()
                    * ReaderActivity.TAP_SCROLL_FRACTION);
            assertEquals("no leftover negative debt after hitting the wall",
                    step, list.computeVerticalScrollOffset());
        }
    }

    // ------------------------------------------------------------------ precedence & chrome

    @Test
    public void withSettingOnATapOnAMagnifiedPageResetsZoomInsteadOfTurning() throws Exception {
        setPrefs("pages", "ltr", true);
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView rv = pagerRecycler(activity);
            TestSupport.waitUntil(() -> rv != null && rv.getChildCount() > 0, 5000, "pager item");
            PageView page = (PageView) rv.getChildAt(0);
            int before = activity.getCurrentPage();

            activity.runOnUiThread(() -> pinch(page));
            TestSupport.idle();
            assertTrue(page.isZoomed());

            tap(activity, 0.92f, 0.5f);
            assertFalse("zoom reset wins over the page-turn zone", page.isZoomed());
            assertEquals("and the page does not turn", before, activity.getCurrentPage());
        }
    }

    @Test
    public void withSettingOnTapsOnToolbarsNeverNavigate() throws Exception {
        setPrefs("pages", "ltr", true);
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            View topBar = activity.findViewById(R.id.top_bar);
            View bottomBar = activity.findViewById(R.id.bottom_bar);
            assertTrue(topBar.getHeight() > 0 && bottomBar.getHeight() > 0);

            // Tap the toolbar title area (not the nav/menu buttons, which stay clickable).
            tapAt(activity, topBar.getWidth() * 0.45f, topBar.getHeight() * 0.5f);
            assertEquals("a tap on the top bar never turns the page",
                    0, activity.getCurrentPage());
            assertTrue("and never hides the chrome", activity.isChromeVisible());

            // Tap the page label inside the bottom bar. (The slider itself is excluded:
            // tapping it legitimately seeks — that is the slider's own contract, and it
            // predates the tap-zone setting.)
            View pageLabel = activity.findViewById(R.id.page_label);
            int[] loc = new int[2];
            pageLabel.getLocationInWindow(loc);
            tapAt(activity, loc[0] + pageLabel.getWidth() * 0.5f,
                    loc[1] + pageLabel.getHeight() * 0.5f);
            assertEquals("a tap on the bottom bar never turns the page",
                    0, activity.getCurrentPage());
            assertTrue("and never hides the chrome", activity.isChromeVisible());
        }
    }

    // ------------------------------------------------------------------ persistence & sheet

    @Test
    public void theSettingPersistsAcrossPreferenceReloadsAndReaderSessions() throws Exception {
        ReaderPrefs off = new ReaderPrefs(app);
        assertFalse(off.tapToTurn);

        off.tapToTurn = true;
        off.save();
        assertTrue("reload sees the saved value", new ReaderPrefs(app).tapToTurn);

        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> first = open(book)) {
            ReaderActivity a1 = TestSupport.currentActivity(first);
            tap(a1, 0.92f, 0.5f);
            assertEquals(1, a1.getCurrentPage());
        }
        // A brand-new reader session must honour the stored preference.
        try (ActivityScenario<ReaderActivity> second = open(book)) {
            ReaderActivity a2 = TestSupport.currentActivity(second);
            tap(a2, 0.92f, 0.5f);
            assertEquals("tap zones still enabled in a fresh session",
                    2, a2.getCurrentPage());

            ReaderPrefs disable = new ReaderPrefs(app);
            disable.tapToTurn = false;
            disable.save();
        }
        try (ActivityScenario<ReaderActivity> third = open(book)) {
            ReaderActivity a3 = TestSupport.currentActivity(third);
            int page = a3.getCurrentPage();
            tap(a3, 0.92f, 0.5f);
            assertEquals("switching the setting off disarms the zones in new sessions",
                    page, a3.getCurrentPage());
        }
    }

    @Test
    public void appearanceSheetExposesTheSwitchOffByDefaultAndAppliesItLive() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.findViewById(R.id.top_bar)
                    .performLongClick());
            openAppearance(activity);
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            MaterialSwitch toggle = sheet.findViewById(R.id.tap_to_turn);
            assertNotNull("the Appearance sheet exposes the tap-zones switch", toggle);
            assertFalse("and it starts off, matching the default", toggle.isChecked());

            // Flipping the switch saves and reaches the open reader without a relayout.
            activity.runOnUiThread(() -> toggle.setChecked(true));
            TestSupport.idle();
            assertTrue(new ReaderPrefs(app).tapToTurn);

            activity.runOnUiThread(sheet::dismiss);
            TestSupport.idle();
            assertEquals(0, activity.getCurrentPage());
            tap(activity, 0.92f, 0.5f);
            assertEquals("the live reader immediately honours the enabled gesture",
                    1, activity.getCurrentPage());

            // And switching it back off disarms the zones just as fast.
            openAppearance(activity);
            BottomSheetDialog sheet2 = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            MaterialSwitch toggle2 = sheet2.findViewById(R.id.tap_to_turn);
            assertTrue("the switch reflects the persisted state", toggle2.isChecked());
            activity.runOnUiThread(() -> toggle2.setChecked(false));
            TestSupport.idle();
            activity.runOnUiThread(sheet2::dismiss);
            TestSupport.idle();

            tap(activity, 0.92f, 0.5f);
            assertEquals("disabling live stops edge taps from turning pages",
                    1, activity.getCurrentPage());
            assertFalse("disabled taps fall back to toggling the chrome",
                    activity.isChromeVisible());
        }
    }

    @Test
    public void theSettingAppliesToPdfBooksInBothViewModesToo() throws Exception {
        PdfSession.sourceFactory = (context, b) -> new TestPdfSource(6);
        Book pdf = TestSupport.installFakePdf(app, "Tap Zones PDF");

        // OFF: taps inert for navigation.
        setPrefs("pages", "ltr", false);
        try (ActivityScenario<ReaderActivity> scenario = open(pdf)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            tap(activity, 0.92f, 0.5f);
            assertEquals("PDF pages mode ignores edge taps while off",
                    0, activity.getCurrentPage());
        }

        // ON: pages mode navigates, scroll mode uses the same reduced 60% step.
        setPrefs("pages", "ltr", true);
        try (ActivityScenario<ReaderActivity> scenario = open(pdf)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            tap(activity, 0.92f, 0.5f);
            assertEquals("PDF pages mode advances when enabled", 1, activity.getCurrentPage());
        }

        setPrefs("scroll", "ltr", true);
        try (ActivityScenario<ReaderActivity> scenario = open(pdf)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            RecyclerView list = activity.findViewById(R.id.scroll_list);
            int before = list.computeVerticalScrollOffset();
            tap(activity, 0.92f, 0.5f);
            TestSupport.idle();
            int step = (int) (activity.findViewById(R.id.content_frame).getHeight()
                    * ReaderActivity.TAP_SCROLL_FRACTION);
            int moved = list.computeVerticalScrollOffset() - before;
            assertTrue("PDF scroll tap moves the reduced step (moved=" + moved
                            + ", step=" + step + ")",
                    moved > 0 && moved <= step);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void setPrefs(String viewMode, String direction, boolean tapToTurn) {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = viewMode;
        prefs.direction = direction;
        prefs.tapToTurn = tapToTurn;
        prefs.save();
    }

    /** Opens the Appearance sheet exactly like a real toolbar click. */
    private void openAppearance(ReaderActivity activity) {
        com.google.android.material.appbar.MaterialToolbar bar =
                activity.findViewById(R.id.top_bar);
        activity.runOnUiThread(() -> bar.getMenu()
                .performIdentifierAction(R.id.action_appearance, 0));
        TestSupport.idle();
    }

    private ActivityScenario<ReaderActivity> open(Book book) {
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        scenario.onActivity(TestSupport::layout);
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 15000, "reader");
        TestSupport.layout(activity);
        TestSupport.idle();
        return scenario;
    }

    private void tap(ReaderActivity activity, float xFraction, float yFraction) {
        int w = activity.findViewById(R.id.content_frame).getWidth();
        int h = activity.findViewById(R.id.content_frame).getHeight();
        tapAt(activity, w * xFraction, h * yFraction);
    }

    private void tapAt(ReaderActivity activity, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0);
        activity.runOnUiThread(() -> {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
        });
        down.recycle();
        up.recycle();
        TestSupport.idle();
    }

    private static void advanceFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            boolean[] vsyncFired = {false};
            android.view.Choreographer.getInstance()
                    .postFrameCallback(ns -> vsyncFired[0] = true);
            while (!vsyncFired[0]) {
                Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
            }
        }
    }

    private static RecyclerView pagerRecycler(ReaderActivity activity) {
        View pager = activity.findViewById(R.id.pager);
        return (RecyclerView) ((ViewGroup) pager).getChildAt(0);
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
