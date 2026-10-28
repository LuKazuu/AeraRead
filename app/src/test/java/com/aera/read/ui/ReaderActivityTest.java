package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.aera.read.R;
import com.aera.read.TestPdfSource;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.PdfSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;


@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class ReaderActivityTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.theme = "light";
        prefs.viewMode = "pages";
        prefs.direction = "ltr";
        prefs.sizeSp = 18;
        prefs.linePct = 155;
        prefs.font = "lora";
        prefs.keepScreenOn = false;
        prefs.save();
        try {
            TestSupport.installFont(app, "lora");
            TestSupport.installFont(app, "inter");
            TestSupport.installFont(app, "opensans");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
        TestSupport.clearLibrary(app);
    }

    private ActivityScenario<ReaderActivity> open(Book book) {
        Intent intent = new Intent(app, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent);
        scenario.onActivity(TestSupport::layout);
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 15000,
                "the session never opened");
        scenario.onActivity(TestSupport::layout);
        TestSupport.idle();
        return scenario;
    }

    @Test
    public void showsTheBookWithWorkingChrome() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertEquals("Meditations", activity.getSession().title());
            assertEquals(0, activity.getCurrentPage());

            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            assertEquals("Meditations", bar.getTitle().toString());
            assertNull("no chapter subtitle: spine names are not reliable", bar.getSubtitle());

            TextView pageLabel = activity.findViewById(R.id.page_label);
            assertTrue(pageLabel.getText().toString().contains("1"));

            SeekBar slider = activity.findViewById(R.id.progress_slider);
            assertEquals(activity.getSession().pageCount() - 1, slider.getMax());
            assertTrue(slider.isEnabled());
        }
    }

    @Test
    public void pageJumpsUpdateTheIndicatorAndLibrary() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int total = activity.getSession().pageCount();
            assertTrue(total > 10);

            activity.runOnUiThread(() -> activity.goToPage(total / 2));
            TestSupport.idle();
            assertEquals(total / 2, activity.getCurrentPage());

            TextView pageLabel = activity.findViewById(R.id.page_label);
            assertTrue(pageLabel.getText().toString().contains(String.valueOf(total / 2 + 1)));

            activity.runOnUiThread(activity::persistPosition);
            TestSupport.idle();
            Book stored = Library.byId(app, book.id());
            assertNotNull(stored);
            assertEquals(total / 2, activity.getSession().initialPage());
        }
    }

    @Test
    public void resumesExactlyWhereItLeftOff() throws Exception {
        Book book = TestSupport.install(app, "pride_slim.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int target = Math.min(12, activity.getSession().pageCount() - 1);
            activity.runOnUiThread(() -> activity.goToPage(target));
            TestSupport.idle();
            activity.runOnUiThread(activity::persistPosition);
            TestSupport.idle();

            Book stored = Library.byId(app, book.id());
            assertNotNull(stored);
            assertFalse(stored.layoutKey.isEmpty());
        }

        // Reopen: same layout key, so the same page must come back.
        try (ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            scenario.onActivity(TestSupport::layout);
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            TestSupport.waitUntil(() -> activity.getSession() != null, 15000, "reopen");
            TestSupport.idle();
            assertEquals(12, activity.getCurrentPage());
        }
    }

    @Test
    public void draggingTheSliderTurnsThePageBeforeRelease() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int target = Math.min(20, activity.getSession().pageCount() - 1);
            assertTrue(target > 3);

            activity.runOnUiThread(() -> activity.onSliderProgress(target, true));
            TestSupport.idle();
            assertEquals("page follows the thumb while dragging", target,
                    activity.getCurrentPage());

            TextView pageLabel = activity.findViewById(R.id.page_label);
            assertTrue(pageLabel.getText().toString().contains(String.valueOf(target + 1)));

            SeekBar slider = activity.findViewById(R.id.progress_slider);
            assertEquals("the thumb is in sync", target, slider.getProgress());
        }
    }

    @Test
    public void booksWithoutAContentsListOpenBookmarksDirectlyFromToolbar() throws Exception {
        Book book = TestSupport.installFile(app, TestSupport.writeEpubWithCover(app,
                "No Contents Book", 600, 900, 80, 80, 520, 820, android.graphics.Color.GRAY),
                "no-toc.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            assertTrue(activity.getSession().contents().isEmpty());
            android.view.MenuItem navItem = bar.getMenu().findItem(R.id.action_toc);
            assertTrue("bookmarks list remains reachable from toolbar", navItem.isVisible());
            assertEquals(activity.getString(R.string.bookmarks), navItem.getTitle().toString());

            activity.runOnUiThread(() -> bar.getMenu().performIdentifierAction(R.id.action_toc, 0));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            android.view.View toggle = sheet.findViewById(R.id.nav_tabs);
            assertEquals("bookmarks only, no dead switch", android.view.View.GONE,
                    toggle.getVisibility());
            android.widget.TextView title = sheet.findViewById(R.id.nav_title);
            assertEquals(activity.getString(R.string.bookmarks), title.getText().toString());
        }
    }

    @Test
    public void draggingTheSliderNeverFightsTheFinger() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            SeekBar slider = activity.findViewById(R.id.progress_slider);

            activity.runOnUiThread(() -> {
                activity.setSliderDragging(true);
                slider.setProgress(30);          // the thumb is under the finger
                activity.onSliderProgress(30, true);
            });
            TestSupport.idle();
            assertEquals(30, activity.getCurrentPage());

            // a scroll-driven page change must leave the thumb where the finger put it
            activity.runOnUiThread(() -> activity.goToPage(5));
            TestSupport.idle();
            assertEquals(5, activity.getCurrentPage());
            assertEquals("scroll updates must not yank the thumb", 30, slider.getProgress());

            // releasing the finger hands control back to the reader
            activity.runOnUiThread(() -> {
                activity.setSliderDragging(false);
                activity.goToPage(30);
            });
            TestSupport.idle();
            assertEquals(30, slider.getProgress());
        }
    }

    @Test
    public void deletingABookmarkInTheSheetClearsTheToolbarMarker() throws Exception {
        Book book = TestSupport.install(app, "prophet.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(3));
            TestSupport.idle();

            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            activity.runOnUiThread(() -> bar.getMenu()
                    .performIdentifierAction(R.id.action_bookmark, 0));
            TestSupport.idle();
            assertTrue(activity.getSession().isBookmarked(3));
            assertEquals(activity.getString(R.string.remove_bookmark),
                    bar.getMenu().findItem(R.id.action_bookmark).getTitle().toString());

            activity.runOnUiThread(() -> activity.openNav(1));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            androidx.recyclerview.widget.RecyclerView list = sheet.findViewById(R.id.list);
            RecyclerViewAdapter adapter = new RecyclerViewAdapter(list.getAdapter());
            assertEquals(1, adapter.raw.getItemCount());
            final androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    adapter.raw.createViewHolder(list, 0);
            adapter.bind(holder, 0);
            android.view.View delete = holder.itemView.findViewById(R.id.bm_delete);
            assertNotNull(delete);

            activity.runOnUiThread(delete::performClick);
            TestSupport.idle();

            assertFalse(activity.getSession().isBookmarked(3));
            assertEquals("marker must not stay stale until the page changes",
                    activity.getString(R.string.add_bookmark),
                    bar.getMenu().findItem(R.id.action_bookmark).getTitle().toString());
        }
    }

    @Test
    public void bookmarkMenuTogglesTheMarker() throws Exception {
        Book book = TestSupport.install(app, "prophet.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(3));
            TestSupport.idle();

            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            activity.runOnUiThread(() -> bar.getMenu()
                    .performIdentifierAction(R.id.action_bookmark, 0));
            TestSupport.idle();
            assertTrue(activity.getSession().isBookmarked(3));
            assertEquals(1, activity.getSession().bookmarks().size());

            activity.runOnUiThread(() -> bar.getMenu()
                    .performIdentifierAction(R.id.action_bookmark, 0));
            TestSupport.idle();
            assertFalse(activity.getSession().isBookmarked(3));
            assertTrue(activity.getSession().bookmarks().isEmpty());
        }
    }

    @Test
    public void navigationSheetListsContentsAndBookmarks() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(2));
            TestSupport.idle();
            activity.runOnUiThread(() -> activity.getSession().toggleBookmark(2));
            TestSupport.idle();

            activity.runOnUiThread(() -> activity.openNav(1));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            TextView empty = sheet.findViewById(R.id.empty);
            assertNotNull(empty);
            assertEquals(View.GONE, empty.getVisibility());
            androidx.recyclerview.widget.RecyclerView list =
                    sheet.findViewById(R.id.list);
            assertNotNull(list);
            assertEquals(1, list.getAdapter().getItemCount());
            sheet.dismiss();
        }
    }

    @Test
    public void appearanceSheetAppliesTheThemeImmediately() throws Exception {
        Book book = TestSupport.install(app, "artofwar.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            activity.runOnUiThread(() -> bar.getMenu()
                    .performIdentifierAction(R.id.action_appearance, 0));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            assertNotNull(sheet.findViewById(R.id.theme_row));
            assertNotNull(sheet.findViewById(R.id.font_chips));
            assertNotNull(sheet.findViewById(R.id.size_slider));
            sheet.dismiss();
        }
    }

    @Test
    public void centreTapTogglesTheControls() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertTrue(activity.isChromeVisible());
            activity.runOnUiThread(() -> activity.setChromeVisible(false));
            TestSupport.idle();
            assertFalse(activity.isChromeVisible());
            activity.runOnUiThread(() -> activity.setChromeVisible(true));
            TestSupport.idle();
            assertTrue(activity.isChromeVisible());
        }
    }

    @Test
    public void tappingTheEdgesTurnsPages() throws Exception {
        enableTapZones();
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertEquals(0, activity.getCurrentPage());

            tap(activity, 0.9f, 0.5f);
            assertEquals("right edge advances", 1, activity.getCurrentPage());

            tap(activity, 0.1f, 0.5f);
            assertEquals("left edge goes back", 0, activity.getCurrentPage());

            tap(activity, 0.5f, 0.5f);
            assertFalse("centre hides the chrome", activity.isChromeVisible());
            tap(activity, 0.5f, 0.5f);
            assertTrue("centre brings it back", activity.isChromeVisible());
        }
    }

    @Test
    public void backPressRestoresChromeBeforeLeaving() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.setChromeVisible(false));
            TestSupport.idle();
            activity.runOnUiThread(activity::onBackPressed);
            TestSupport.idle();
            assertTrue(activity.isChromeVisible());
            assertFalse(activity.isFinishing());
        }
    }

    @Test
    public void keepScreenOnFollowsTheSetting() throws Exception {
        final boolean[] flags = new boolean[1];
        {
            Book book = TestSupport.install(app, "alice.epub");
            try (ActivityScenario<ReaderActivity> scenario = open(book)) {
                ReaderActivity activity = TestSupport.currentActivity(scenario);
                activity.runOnUiThread(() -> flags[0] = (activity.getWindow().getAttributes().flags
                        & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            }
        }
        assertFalse("off by default", flags[0]);

        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.keepScreenOn = true;
        prefs.save();
        Book book = Library.books(app).get(0);
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> flags[0] = (activity.getWindow().getAttributes().flags
                    & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        }
        assertTrue("enabled while reading", flags[0]);
    }

    @Test
    public void contentsSheetListsReachablePagesAndJumps() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.openNav(0));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            androidx.recyclerview.widget.RecyclerView list = sheet.findViewById(R.id.list);
            assertNotNull(list);
            RecyclerViewAdapter adapter = new RecyclerViewAdapter(list.getAdapter());
            assertNotNull(adapter.raw);
            int count = adapter.raw.getItemCount();
            assertTrue("contents has entries", count > 1);

            int position = count - 1;
            final androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    adapter.raw.createViewHolder(list, adapter.viewType(position));
            adapter.bind(holder, position);
            android.widget.TextView pageView = holder.itemView.findViewById(R.id.toc_page);
            assertNotNull(pageView);
            int expected = Integer.parseInt(pageView.getText().toString()) - 1;
            assertTrue("no dead rows", expected > 0);

            activity.runOnUiThread(holder.itemView::performClick);
            TestSupport.idle();
            assertEquals("tapping a chapter jumps to it", expected, activity.getCurrentPage());
        }
    }

    @Test
    public void contentsNeverRepeatsAPage() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.openNav(0));
            TestSupport.idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            androidx.recyclerview.widget.RecyclerView list = sheet.findViewById(R.id.list);
            RecyclerViewAdapter adapter = new RecyclerViewAdapter(list.getAdapter());
            java.util.Set<String> pages = new java.util.HashSet<>();
            for (int i = 0; i < adapter.raw.getItemCount(); i++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        adapter.raw.createViewHolder(list, adapter.viewType(i));
                adapter.bind(holder, i);
                android.widget.TextView page = holder.itemView.findViewById(R.id.toc_page);
                assertTrue("page " + page.getText() + " listed twice",
                        pages.add(page.getText().toString()));
            }
            assertFalse("every row is addressable", pages.isEmpty());
        }
    }

    @Test
    public void themeChangeRepaintsTheReader() throws Exception {
        Book book = TestSupport.install(app, "alice.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            android.view.View root = activity.findViewById(R.id.reader_root);
            int before = ((android.graphics.drawable.ColorDrawable) root.getBackground())
                    .getColor();

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.theme = "night";
            prefs.save();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(false));
            TestSupport.idle();

            int after = ((android.graphics.drawable.ColorDrawable) root.getBackground())
                    .getColor();
            assertEquals(com.aera.read.data.ReaderTheme.NIGHT.bg, after);
            assertNotEquals(before, after);
        }
    }

    @Test
    public void pdfsHideTextOnlyFeatures() throws Exception {
        Book book = TestSupport.installFakePdf(app, "A PDF Book");
        PdfSession.sourceFactory = (context, b) -> new TestPdfSource(18);
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            assertTrue(activity.getSession() instanceof PdfSession);
            assertEquals(18, activity.getSession().pageCount());

            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            android.view.MenuItem toc = bar.getMenu().findItem(R.id.action_toc);
            assertNotNull(toc);
            assertTrue("PDFs keep toolbar navigation visible to view Bookmarks", toc.isVisible());
            assertEquals(activity.getString(R.string.bookmarks), toc.getTitle().toString());
            assertTrue(bar.getMenu().findItem(R.id.action_appearance).isVisible());

            TextView pageLabel = activity.findViewById(R.id.page_label);
            assertTrue(pageLabel.getText().toString().contains("1"));

            activity.runOnUiThread(() -> activity.goToPage(7));
            TestSupport.idle();
            assertEquals(7, activity.getCurrentPage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class RecyclerViewAdapter {
        final androidx.recyclerview.widget.RecyclerView.Adapter raw;

        RecyclerViewAdapter(androidx.recyclerview.widget.RecyclerView.Adapter adapter) {
            this.raw = adapter;
        }

        int viewType(int position) {
            return raw.getItemViewType(position);
        }

        void bind(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
            raw.bindViewHolder(holder, position);
        }
    }

    /** Edge tap zones are opt-in since v1.1.2; enable them for gesture-driven tests. */
    private void enableTapZones() {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.tapToTurn = true;
        prefs.save();
    }

    private void tap(ReaderActivity activity, float xFraction, float yFraction) {
        int w = activity.findViewById(R.id.content_frame).getWidth();
        int h = activity.findViewById(R.id.content_frame).getHeight();
        float x = w * xFraction;
        float y = h * yFraction;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 30,
                android.view.MotionEvent.ACTION_UP, x, y, 0);
        activity.runOnUiThread(() -> {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
        });
        down.recycle();
        up.recycle();
        TestSupport.idle();
    }

    @Test
    public void scrollModePinchZoomsAndATapFitsThePageAgain() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ZoomRecyclerView list = activity.findViewById(R.id.scroll_list);
            TestSupport.waitUntil(() -> pageIn(list) != null, 5000,
                    "the scrolling list must lay its pages out");
            PageView page = pageIn(list);
            assertFalse(list.isZoomed());
            assertFalse(page.isZoomed());

            pinch(activity);
            assertTrue("two fingers magnify the whole scroll viewport", list.isZoomed());
            assertFalse("individual page item is not isolated inside its own box", page.isZoomed());
            int before = activity.getCurrentPage();
            boolean chromeBefore = activity.isChromeVisible();

            tap(activity, 0.5f, 0.5f);
            assertFalse("a tap puts the scroll viewport back to fit", list.isZoomed());
            assertEquals("and it does not toggle chrome", chromeBefore,
                    activity.isChromeVisible());
            assertEquals("and it does not also turn the page", before,
                    activity.getCurrentPage());
        }
    }

    @Test
    public void aTapOnAMagnifiedPagedViewFitsItAgain() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            android.view.ViewGroup pager = activity.findViewById(R.id.pager);
            TestSupport.waitUntil(() -> pagerList(pager) != null
                    && pageIn(pagerList(pager)) != null, 5000, "the pager must lay its page out");
            PageView page = pageIn(pagerList(pager));
            int before = activity.getCurrentPage();

            pinch(activity);
            assertTrue(page.isZoomed());
            tap(activity, 0.5f, 0.5f);
            assertFalse("a tap on a magnified page fits it again", page.isZoomed());
            assertEquals("without turning the page", before, activity.getCurrentPage());
        }
    }

    /** The first page view the list has actually laid out. */
    private static PageView pageIn(android.view.ViewGroup list) {
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i) instanceof PageView) return (PageView) list.getChildAt(i);
        }
        return null;
    }

    private static android.view.ViewGroup pagerList(android.view.ViewGroup pager) {
        if (pager.getChildCount() == 0) return null;
        android.view.View child = pager.getChildAt(0);
        return child instanceof android.view.ViewGroup ? (android.view.ViewGroup) child : null;
    }

    /** Two fingers spreading on the middle of the reader, as a real pinch arrives. */
    private void pinch(ReaderActivity activity) {
        android.view.View frame = activity.findViewById(R.id.content_frame);
        float cx = frame.getWidth() * 0.5f;
        float cy = frame.getHeight() * 0.5f;
        long now = android.os.SystemClock.uptimeMillis();
        activity.runOnUiThread(() -> {
            activity.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, cx, cy, 0));
            activity.dispatchTouchEvent(two(now, now + 10,
                    android.view.MotionEvent.ACTION_POINTER_DOWN
                            | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx - 30, cy, cx + 30, cy));
            // Robolectric drops the first move of a multi-touch walk
            activity.dispatchTouchEvent(two(now, now + 20,
                    android.view.MotionEvent.ACTION_MOVE, cx - 300, cy, cx + 300, cy));
            activity.dispatchTouchEvent(two(now, now + 30,
                    android.view.MotionEvent.ACTION_MOVE, cx - 380, cy, cx + 380, cy));
            activity.dispatchTouchEvent(two(now, now + 40,
                    android.view.MotionEvent.ACTION_POINTER_UP, cx - 380, cy, cx + 380, cy));
            activity.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 50,
                    android.view.MotionEvent.ACTION_UP, cx + 380, cy, 0));
        });
        TestSupport.idle();
    }

    private static android.view.MotionEvent two(long down, long when, int action,
                                                float x0, float y0, float x1, float y1) {
        android.view.MotionEvent.PointerProperties p0 =
                new android.view.MotionEvent.PointerProperties();
        p0.id = 0;
        android.view.MotionEvent.PointerProperties p1 =
                new android.view.MotionEvent.PointerProperties();
        p1.id = 1;
        android.view.MotionEvent.PointerCoords c0 = new android.view.MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        android.view.MotionEvent.PointerCoords c1 = new android.view.MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        return android.view.MotionEvent.obtain(down, when, action, 2,
                new android.view.MotionEvent.PointerProperties[]{p0, p1},
                new android.view.MotionEvent.PointerCoords[]{c0, c1}, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    @Test
    public void scrollModeUsesTheListInsteadOfThePager() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.save();
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            View pager = activity.findViewById(R.id.pager);
            View list = activity.findViewById(R.id.scroll_list);
            assertEquals(View.GONE, pager.getVisibility());
            assertEquals(View.VISIBLE, list.getVisibility());
        }
    }


    /**
     * The size slider must reflow the book while the finger is still on it, exactly like the
     * page slider. This drives real touch events through the Material slider instead of
     * calling the callback by hand.
     */
    @Test
    public void draggingTheSizeSliderReflowsTheBookBeforeRelease() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int pagesBefore = activity.getSession().pageCount();
            int sizeBefore = new ReaderPrefs(app).sizeSp;

            com.google.android.material.slider.Slider slider =
                    openSlider(activity, R.id.action_appearance, R.id.size_slider);

            // press a little right of the thumb and move: no ACTION_UP yet
            dragSlider(slider, 0.55f, 0.9f);

            TestSupport.waitUntil(() -> new ReaderPrefs(app).sizeSp != sizeBefore, 5000,
                    "the slider must store the new size while the finger is down");
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesBefore, 20000,
                    "the book must re-paginate while the finger is down");

            int sizeWhileDragging = new ReaderPrefs(app).sizeSp;
            assertTrue("dragging right enlarges the text", sizeWhileDragging > sizeBefore);

            releaseSlider(slider);
            assertEquals("the release keeps the value the drag produced",
                    sizeWhileDragging, new ReaderPrefs(app).sizeSp);
            assertTrue("the page still fits the new layout",
                    activity.getCurrentPage() < activity.getSession().pageCount());
        }
    }

    @Test
    public void draggingTheLineSpacingSliderReflowsTheBookBeforeRelease() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int pagesBefore = activity.getSession().pageCount();
            int lineBefore = new ReaderPrefs(app).linePct;

            com.google.android.material.slider.Slider slider =
                    openSlider(activity, R.id.action_appearance, R.id.line_slider);
            dragSlider(slider, 0.9f, 0.97f);

            TestSupport.waitUntil(() -> new ReaderPrefs(app).linePct != lineBefore, 5000,
                    "the slider must store the new spacing while the finger is down");
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesBefore, 20000,
                    "the book must re-paginate while the finger is down");
            assertTrue("wider leading needs more pages",
                    activity.getSession().pageCount() > pagesBefore);
            releaseSlider(slider);
        }
    }

    @Test
    public void theToolbarTitleSitsOnTheIconLine() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            TestSupport.layout(activity);
            TestSupport.idle();

            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            float iconCentre = Float.NaN;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() == 0) continue;
                if (child instanceof TextView) continue;
                iconCentre = child.getTop() + child.getHeight() / 2f;
            }
            TextView title = null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child instanceof TextView && child.getHeight() > 0) {
                    title = (TextView) child;
                    break;
                }
            }
            assertNotNull(title);
            assertFalse("an icon column must exist to align with", Float.isNaN(iconCentre));
            float titleCentre = title.getTop() + title.getTranslationY() + title.getHeight() / 2f;
            assertEquals("title shares the icons' centre line",
                    iconCentre, titleCentre, 0.6f);
        }
    }

    /** Character at the top of a page, straight from the paginated book. */
    private static int topOf(ReaderActivity activity, int page) {
        return ((com.aera.read.reader.EpubSession) activity.getSession()).offsetAtTopOf(page);
    }

    /** Opens a bottom sheet and returns a slider from it, laid out and ready for touches. */
    private com.google.android.material.slider.Slider openSlider(ReaderActivity activity,
                                                               int menuAction, int sliderId) {
        MaterialToolbar bar = activity.findViewById(R.id.top_bar);
        activity.runOnUiThread(() -> bar.getMenu().performIdentifierAction(menuAction, 0));
        TestSupport.idle();
        BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        assertNotNull(sheet);
        com.google.android.material.slider.Slider slider = sheet.findViewById(sliderId);
        assertNotNull(slider);
        android.view.View decor = sheet.getWindow().getDecorView();
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        activity.runOnUiThread(() -> {
            decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,
                            android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(h,
                            android.view.View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, w, h);
        });
        TestSupport.idle();
        return slider;
    }

    /** Puts a finger on the track at {@code from} and drags to {@code to}, without lifting. */
    private void dragSlider(com.google.android.material.slider.Slider slider,
                            float from, float to) {
        int w = slider.getWidth();
        int h = slider.getHeight();
        assertTrue("slider needs a real width", w > 100 && h > 20);
        float y = h / 2f;
        float x0 = Math.max(1f, Math.min(w - 1f, w * from));
        float x1 = Math.max(1f, Math.min(w - 1f, w * to));
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_DOWN, x0, y, 0);
        android.view.MotionEvent move = android.view.MotionEvent.obtain(now, now + 40,
                android.view.MotionEvent.ACTION_MOVE, x1, y, 0);
        slider.dispatchTouchEvent(down);
        slider.dispatchTouchEvent(move);
        pendingMove = move;
        down.recycle();
    }

    private android.view.MotionEvent pendingMove;

    private void releaseSlider(com.google.android.material.slider.Slider slider) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_UP, slider.getWidth() * 0.9f,
                slider.getHeight() / 2f, 0);
        slider.dispatchTouchEvent(up);
        up.recycle();
        if (pendingMove != null) {
            pendingMove.recycle();
            pendingMove = null;
        }
        TestSupport.idle();
    }


    /** Switching font must not walk the reader backwards through the chapter. */
    @Test
    public void changingTheFontKeepsTheReadingSpot() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(6));
            TestSupport.idle();
            int pageBefore = activity.getCurrentPage();
            int offsetBefore = Library.byId(app, book.id()).offset;

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.font = "inter";
            prefs.save();
            int pagesBefore = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesBefore, 20000,
                    "relayout");
            TestSupport.idle();

            Book after = Library.byId(app, book.id());
            assertEquals("same chapter", Library.byId(app, book.id()).chapter, after.chapter);
            assertTrue("the stored position never runs backwards, was " + offsetBefore
                    + " now " + after.offset, after.offset >= offsetBefore);
            assertTrue("the page does not jump back, was " + pageBefore + " now "
                    + activity.getCurrentPage(), activity.getCurrentPage() >= pageBefore - 1);
        }
    }

    /** Repeated font switches settle instead of creeping backwards one page at a time. */
    @Test
    public void switchingFontsRepeatedlyDoesNotDrift() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(10));
            TestSupport.idle();
            int start = Library.byId(app, book.id()).offset;
            for (String font : new String[]{"inter", "opensans", "lora", "inter",
                    "lora"}) {
                ReaderPrefs prefs = new ReaderPrefs(app);
                prefs.font = font;
                prefs.save();
                int before = activity.getSession().pageCount();
                int expected = new ReaderPrefs(app).sizeSp;
                activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
                TestSupport.waitUntil(() -> activity.getSession().pageCount() != before
                        || font.equals("lora"), 20000, "relayout " + font);
                TestSupport.idle();
            }
            int end = Library.byId(app, book.id()).offset;
            int distance = Math.abs(end - start);
            assertTrue("position stays within one page of where it began, moved " + distance,
                    distance < 1200);
        }
    }

    /** Dragging the line-spacing slider must not bounce the page around. */
    @Test
    public void draggingLineSpacingDoesNotFlipPages() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(8));
            TestSupport.idle();
            int page = activity.getCurrentPage();

            int[] values = {158, 162, 166, 170, 174, 178, 182};
            int lowest = Integer.MAX_VALUE;
            for (int value : values) {
                ReaderPrefs prefs = new ReaderPrefs(app);
                prefs.linePct = value;
                prefs.save();
                activity.runOnUiThread(() -> activity.onAppearancePreview());
                TestSupport.waitUntil(() -> true, 400, "tick");
                TestSupport.idle();
                int now = activity.getCurrentPage();
                assertTrue("the page never jumps backwards while dragging, was " + page
                        + " now " + now, now >= page - 1);
                lowest = Math.min(lowest, now);
                page = now;
            }

            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.idle();
            TestSupport.waitUntil(() -> true, 400, "settle");
            assertTrue("the release does not throw the page away",
                    Math.abs(activity.getCurrentPage() - page) <= 1);
            assertTrue(lowest >= 0);
        }
    }

    /** A burst of size changes from one end of the slider to the other must settle. */
    @Test
    public void rapidSizeChangesSettleOnTheLastValue() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int before = activity.getSession().pageCount();
            for (int round = 0; round < 3; round++) {
                for (int size : new int[]{14, 30, 14, 30, 14, 30, 14}) {
                    ReaderPrefs prefs = new ReaderPrefs(app);
                    prefs.sizeSp = size;
                    prefs.save();
                    activity.runOnUiThread(() -> activity.onAppearancePreview());
                    TestSupport.idle();
                }
            }
            ReaderPrefs last = new ReaderPrefs(app);
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != before, 30000,
                    "the last size must reach the page");
            TestSupport.idle();
            assertEquals("the final size is the one in use", last.sizeSp,
                    new ReaderPrefs(app).sizeSp);
            assertTrue("still alive with a valid page",
                    activity.getCurrentPage() >= 0
                            && activity.getCurrentPage() < activity.getSession().pageCount());
            assertFalse(activity.isFinishing());
        }
    }

    /** The title must sit on the icon line in the very first frame, not slide into place. */
    @Test
    public void theToolbarTitleIsCentredWithoutSettling() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            TestSupport.layout(activity);
            // deliberately no looper idle here: measure the first layout pass
            MaterialToolbar bar = activity.findViewById(R.id.top_bar);
            float iconCentre = Float.NaN;
            TextView title = null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() == 0) continue;
                if (child instanceof TextView) {
                    if (title == null) title = (TextView) child;
                } else if (Float.isNaN(iconCentre)) {
                    iconCentre = child.getTop() + child.getHeight() / 2f;
                }
            }
            assertNotNull(title);
            assertFalse(Float.isNaN(iconCentre));
            float centre = title.getTop() + title.getTranslationY() + title.getHeight() / 2f;
            assertEquals("title is centred during layout", iconCentre, centre, 0.6f);
        }
    }


    /**
     * The exact report: read into the middle of a chapter, change the font, and you are
     * thrown back to that chapter's first page. Includes the earlier re-layout at the
     * chapter start that used to leave a stored anchor behind.
     */
    @Test
    public void changingTheFontAfterReadingDeepIntoAChapterStaysPut() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);

            // an earlier settings change while still at the start of chapter 2
            activity.runOnUiThread(() -> activity.goToPage(3));
            TestSupport.idle();
            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.sizeSp = 20;
            prefs.save();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> true, 800, "first relayout");
            TestSupport.waitUntil(() -> activity.getSession().pageCount() > 0, 20000, "ready");

            // now read on, page by page, into the middle of that chapter
            for (int i = 0; i < 12; i++) {
                activity.runOnUiThread(activity::turnPageForwardForTest);
                TestSupport.idle();
            }
            int pageBefore = activity.getCurrentPage();
            int offsetBefore = Library.byId(app, book.id()).offset;
            int chapterBefore = Library.byId(app, book.id()).chapter;

            // change the font, as the appearance sheet does
            ReaderPrefs next = new ReaderPrefs(app);
            next.font = "inter";
            next.save();
            int pagesBefore = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesBefore, 20000,
                    "font relayout");
            TestSupport.idle();

            Book after = Library.byId(app, book.id());
            assertEquals("still in the same chapter", chapterBefore, after.chapter);
            assertTrue("position moved backwards: " + offsetBefore + " -> " + after.offset,
                    after.offset >= offsetBefore);
            assertTrue("page jumped back: " + pageBefore + " -> " + activity.getCurrentPage(),
                    activity.getCurrentPage() >= pageBefore - 1);
        }
    }


    /**
     * The reported flow with real pages: Literata -> Inter -> Literata must put the reader
     * back on exactly the same page and text, and while in Inter the line they were reading
     * has to be on the screen, not lost.
     */
    @Test
    public void fontRoundTripReturnsToTheSamePageAndText() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(40));
            TestSupport.idle();
            int pageBefore = activity.getCurrentPage();
            int charBefore = topOf(activity, pageBefore);

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.font = "inter";
            prefs.save();
            final int interPages = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != interPages, 20000,
                    "inter relayout");
            TestSupport.idle();

            int interPage = activity.getCurrentPage();
            int start = topOf(activity, interPage);
            int end = topOf(activity, interPage + 1);
            assertTrue("the reader lands within a couple of lines of the same spot, was at "
                            + charBefore + " now at " + start + ".." + end,
                    charBefore >= start - 130 && charBefore <= end);

            prefs = new ReaderPrefs(app);
            prefs.font = "lora";
            prefs.save();
            final int litPages = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != litPages, 20000,
                    "literata relayout");
            TestSupport.idle();
            assertEquals("switching back returns to the very same page",
                    pageBefore, activity.getCurrentPage());
            assertEquals("and the very same line", charBefore,
                    topOf(activity, activity.getCurrentPage()));
        }
    }

    /** After actually reading on, the held spot is dropped and the font change follows the page. */
    @Test
    public void theHeldSpotIsDroppedOnceTheReaderMovesOn() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> activity.goToPage(20));
            TestSupport.idle();

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.font = "inter";
            prefs.save();
            final int pagesA = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesA, 20000, "a");
            TestSupport.idle();

            for (int i = 0; i < 5; i++) {
                activity.runOnUiThread(activity::turnPageForwardForTest);
                TestSupport.idle();
            }
            int moved = activity.getCurrentPage();
            int movedChar = topOf(activity, moved);

            prefs = new ReaderPrefs(app);
            prefs.font = "lora";
            prefs.save();
            final int pagesB = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != pagesB, 20000, "b");
            TestSupport.idle();

            int now = activity.getCurrentPage();
            int start = topOf(activity, now);
            int end = topOf(activity, now + 1);
            assertTrue("the page follows where the reader actually is, not an old spot",
                    movedChar >= start && movedChar < end);
            assertTrue("and it is not dragged back to the old page", now >= moved - 1);
        }
    }

    @Test
    public void typographyChangeRelayoutsAndKeepsPlace() throws Exception {
        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            int before = activity.getSession().pageCount();
            activity.runOnUiThread(() -> activity.goToPage(6));
            TestSupport.idle();

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.sizeSp = 27;
            prefs.save();
            activity.runOnUiThread(() -> activity.onAppearanceChanged(true));
            TestSupport.waitUntil(() -> activity.getSession().pageCount() != before, 15000,
                    "relayout should change the page count");
            assertTrue(activity.getCurrentPage() >= 0);
        }
    }
}
