package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.pdf.PdfPageSource;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.PdfSession;
import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

/**
 * Regression suite covering:
 * 1. Fast PDF scrolling / paging recycling & hardware bounds clipping (no overlapping or ghosted pages).
 * 2. Exact sub-page PDF scroll seam persistence across exit, reopen, and inset/resize relayouts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, qualifiers = "w411dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PdfReaderBugRegressionTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
    }

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
    }

    private static int signatureColor(int page) {
        int[] palette = {
                0xFFD92D20, 0xFF1570EF, 0xFF079455, 0xFFDC6803,
                0xFF7A5AF8, 0xFFBA24D5, 0xFF0E9384, 0xFFE04F16
        };
        return palette[Math.floorMod(page, palette.length)];
    }

    private static PdfPageSource createDistinctPdfSource(int pages, long delayMs,
                                                         boolean variableRatios) {
        return new PdfPageSource() {
            @Override
            public int pageCount() {
                return pages;
            }

            @Override
            public Bitmap render(int page, int targetWidth) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                }
                int w = Math.max(120, targetWidth);
                float ratio = variableRatios
                        ? (page % 3 == 0 ? 1.78f : (page % 3 == 1 ? 1.32f : 1.58f))
                        : 1.78f;
                int h = Math.max(120, Math.round(w * ratio));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(signatureColor(page));
                return bmp;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    public void fastScrollRecyclingClearsStalePagesAndNeverOverlapsInScrollView() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "scroll";
        prefs.theme = "light";
        prefs.pdfFullTheme = true;
        prefs.save();

        Book pdf = TestSupport.installFakePdf(app, "Fast Scroll PDF");
        PdfSession.sourceFactory = (c, b) -> createDistinctPdfSource(28, 28L, true);

        Intent intent = new Intent(app, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());

        try (ActivityController<ReaderActivity> ctrl =
                     Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
            ReaderActivity act = ctrl.get();
            TestSupport.waitUntil(
                    () -> act.findViewById(R.id.loading).getVisibility() == View.GONE,
                    6000, "pdf loaded");

            View root = act.findViewById(R.id.reader_root);
            layoutReader(root, 822, 1480);

            RecyclerView rv = act.findViewById(R.id.scroll_list);
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            assertNotNull(lm);

            // Pinch-zoom page 0 before fast-scrolling so recycled ViewHolders also had zoom state
            View firstChild = lm.findViewByPosition(0);
            assertTrue(firstChild instanceof ZoomImageView);
            ZoomImageView firstZoom = (ZoomImageView) firstChild;
            assertTrue(firstZoom.hasBitmap());
            firstZoom.onPinchScale(2.8f, 411f, 600f);
            assertTrue(firstZoom.isZoomed());

            // Simulate fast multi-page fling bursts across 20+ pages while async renders are in flight
            for (int step = 0; step < 18; step++) {
                rv.scrollBy(0, 920);
                // Inspect attached children immediately (before background renders finish!)
                int childCount = rv.getChildCount();
                assertTrue(childCount > 0);
                View prev = null;
                for (int i = 0; i < childCount; i++) {
                    View child = rv.getChildAt(i);
                    int adapterPos = rv.getChildAdapterPosition(child);
                    assertTrue(child instanceof ZoomImageView);
                    ZoomImageView ziv = (ZoomImageView) child;
                    assertEquals("Recycled view must track its new page immediately",
                            adapterPos, ziv.boundPage());
                    if (adapterPos > 0) {
                        assertFalse("Recycled view must never carry zoom from an old page",
                                ziv.isZoomed());
                    }
                    if (ziv.hasBitmap()) {
                        // Draw this single card and verify its center pixel matches its OWN page
                        Bitmap cardBmp = Bitmap.createBitmap(
                                Math.max(1, ziv.getWidth()),
                                Math.max(1, ziv.getHeight()),
                                Bitmap.Config.ARGB_8888);
                        ziv.draw(new Canvas(cardBmp));
                        int centerColor = cardBmp.getPixel(
                                cardBmp.getWidth() / 2, cardBmp.getHeight() / 2);
                        assertEquals("Bound bitmap on page " + adapterPos
                                        + " must never ghost an earlier recycled page",
                                signatureColor(adapterPos), centerColor);
                        cardBmp.recycle();
                    }
                    if (prev != null) {
                        assertEquals("Adjacent PDF scroll pages must never overlap vertically",
                                prev.getBottom(), child.getTop());
                    }
                    prev = child;
                }
            }

            // Let landing pages finish rendering and verify contiguous non-overlapping layout
            waitForVisibleScrollBitmaps(rv, 6000);
            Robolectric.flushForegroundThreadScheduler();
            View prev = null;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                if (prev != null) {
                    assertEquals("Settled PDF scroll pages must remain contiguous without overlap",
                            prev.getBottom(), child.getTop());
                }
                prev = child;
            }
        }
    }

    @Test
    public void fastPagingAndZoomClippingNeverBleedOrStickInPagesView() throws Exception {
        // 1. Verify ZoomImageView clips strictly to (0, 0, w, h) even when zoomed 4x on a wider canvas
        ZoomImageView ziv = new ZoomImageView(app, false);
        ziv.setTheme(ReaderTheme.LIGHT, true);
        ziv.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        ziv.layout(0, 0, 400, 600);
        Bitmap red = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
        red.eraseColor(0xFFFF0000);
        ziv.show(0, red);
        ziv.onPinchScale(4.0f, 200f, 300f);

        Bitmap wideCanvasBmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888);
        wideCanvasBmp.eraseColor(0xFF00FF00);
        Canvas c = new Canvas(wideCanvasBmp);
        c.save();
        c.translate(250f, 150f);
        ziv.draw(c);
        c.restore();

        // Pixels outside [250..650, 150..750] must remain untouched pure green (never bled red!)
        assertEquals(0xFF00FF00, wideCanvasBmp.getPixel(240, 450));
        assertEquals(0xFF00FF00, wideCanvasBmp.getPixel(660, 450));
        assertEquals(0xFF00FF00, wideCanvasBmp.getPixel(450, 140));
        assertEquals(0xFF00FF00, wideCanvasBmp.getPixel(450, 760));
        assertEquals(0xFFFF0000, wideCanvasBmp.getPixel(450, 450));

        // 2. Verify rapid paging in ReaderActivity Pages View (both LTR and RTL) never ghosts or sticks
        for (String dir : new String[]{"ltr", "rtl"}) {
            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.viewMode = "pages";
            prefs.direction = dir;
            prefs.theme = "light";
            prefs.save();

            Book pdf = TestSupport.installFakePdf(app, "Fast Paged PDF " + dir);
            PdfSession.sourceFactory = (ctx, b) -> createDistinctPdfSource(16, 25L, false);

            Intent intent = new Intent(app, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());

            try (ActivityController<ReaderActivity> ctrl =
                         Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
                ReaderActivity act = ctrl.get();
                TestSupport.waitUntil(
                        () -> act.findViewById(R.id.loading).getVisibility() == View.GONE,
                        6000, "paged pdf loaded");
                View root = act.findViewById(R.id.reader_root);
                layoutReaderAndSettleRelayout(root, 822, 1480);

                ViewPager2 pager = act.findViewById(R.id.pager);
                RecyclerView pagerRv = (RecyclerView) pager.getChildAt(0);

                // Rapidly jump/swipe through pages 0 -> 1 -> 2 -> ... -> 7 before async renders complete
                for (int target = 1; target <= 7; target++) {
                    pager.setCurrentItem(target, false);
                    layoutReader(root, 822, 1480);
                    View active = pagerRv.getLayoutManager().findViewByPosition(target);
                    assertNotNull(active);
                    assertEquals("Active page in ViewPager2 (" + dir + ") must align cleanly at x=0",
                            0, active.getLeft());
                    ZoomImageView pageView = (ZoomImageView) active;
                    assertEquals(target, pageView.boundPage());
                    if (pageView.hasBitmap()) {
                        int pw = Math.max(1, pageView.getWidth());
                        int ph = Math.max(1, pageView.getHeight());
                        Bitmap snap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
                        pageView.draw(new Canvas(snap));
                        assertEquals(signatureColor(target), snap.getPixel(pw / 2, ph / 2));
                        snap.recycle();
                    }
                }
            }
        }
    }

    @Test
    public void midPageSeamScrollPositionPersistsExactPixelAcrossExitAndReopen() throws Exception {
        // Test BOTH a mid-page seam (54% between Page 3 and Page 4) AND a deep bottom seam (97.2%,
        // previously broken by the 0.95f clamp!) on an auto-cropped wide-margin PDF whose cropped
        // aspect ratio (~1.76) differs sharply from raw A4 (1.414).
        for (float targetRatio : new float[]{0.54f, 0.972f}) {
            TestSupport.clearLibrary(app);
            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.viewMode = "scroll";
            prefs.theme = "light";
            prefs.pdfFullTheme = true;
            prefs.save();

            Book pdf = TestSupport.installFakePdf(app, "Seam Persistence PDF " + targetRatio);
            PdfSession.sourceFactory = (ctx, b) -> PdfRendererSource.fromRasterizer(
                    createWideMarginCroppedRasterizer(10));

            Intent intent = new Intent(app, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());

            int expectedPage = 3;
            int seamTopBefore;
            int pageHeightBefore;

            try (ActivityController<ReaderActivity> ctrl =
                         Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
                ReaderActivity act = ctrl.get();
                TestSupport.waitUntil(
                        () -> act.findViewById(R.id.loading).getVisibility() == View.GONE,
                        6000, "initial open");
                View root = act.findViewById(R.id.reader_root);
                layoutReaderAndSettleRelayout(root, 822, 1480);

                RecyclerView rv = act.findViewById(R.id.scroll_list);
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                assertNotNull(lm);

                // Navigate to Page 3, wait for real cropped bitmaps, then scroll to targetRatio!
                act.goToPage(expectedPage);
                layoutReader(root, 822, 1480);
                waitForVisibleScrollBitmaps(rv, 6000);
                layoutReader(root, 822, 1480);

                View page3 = lm.findViewByPosition(expectedPage);
                assertNotNull(page3);
                pageHeightBefore = page3.getHeight();
                // Verify auto-cropped height differs substantially from 822 * 1.414 = 1162
                assertTrue("Cropped PDF page should be taller than raw 1.414 placeholder ("
                        + pageHeightBefore + ")", pageHeightBefore > 1320);

                int scrollDelta = Math.round(pageHeightBefore * targetRatio);
                rv.scrollBy(0, scrollDelta);
                layoutReader(root, 822, 1480);
                waitForVisibleScrollBitmaps(rv, 6000);
                layoutReader(root, 822, 1480);

                View page3AfterScroll = lm.findViewByPosition(expectedPage);
                View page4AfterScroll = lm.findViewByPosition(expectedPage + 1);
                assertNotNull("Page 3 still anchored at top", page3AfterScroll);
                assertNotNull("Page 4 visible right below the seam", page4AfterScroll);
                seamTopBefore = page4AfterScroll.getTop();
                assertEquals(page3AfterScroll.getBottom(), seamTopBefore);

                // Exit the reader (triggers onPause -> persistPosition)
                ctrl.pause().stop();
            }

            Book saved = Library.byId(app, pdf.id());
            assertNotNull(saved);
            assertEquals(expectedPage, saved.offset);
            assertEquals(targetRatio, saved.fraction, 0.01f);

            // Reopen the PDF in a brand-new ReaderActivity and verify the seam pixel position!
            Intent reopenIntent = new Intent(app, ReaderActivity.class);
            reopenIntent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());
            try (ActivityController<ReaderActivity> ctrl2 =
                         Robolectric.buildActivity(ReaderActivity.class, reopenIntent).setup()) {
                ReaderActivity act2 = ctrl2.get();
                TestSupport.waitUntil(
                        () -> act2.findViewById(R.id.loading).getVisibility() == View.GONE,
                        6000, "reopen");
                View root2 = act2.findViewById(R.id.reader_root);
                layoutReaderAndSettleRelayout(root2, 822, 1480);

                // Wait well past any async render window to ensure NO post-load height jump occurs
                RecyclerView rv2 = act2.findViewById(R.id.scroll_list);
                waitForVisibleScrollBitmaps(rv2, 6000);
                Thread.sleep(60);
                Robolectric.flushForegroundThreadScheduler();
                layoutReader(root2, 822, 1480);

                LinearLayoutManager lm2 = (LinearLayoutManager) rv2.getLayoutManager();
                assertNotNull(lm2);
                assertEquals(expectedPage, lm2.findFirstVisibleItemPosition());

                View page3Reopened = lm2.findViewByPosition(expectedPage);
                View page4Reopened = lm2.findViewByPosition(expectedPage + 1);
                assertNotNull(page3Reopened);
                assertNotNull(page4Reopened);
                assertEquals(pageHeightBefore, page3Reopened.getHeight());
                assertTrue("Seam between Page 3 and Page 4 must restore to the exact spot (before="
                                + seamTopBefore + ", after=" + page4Reopened.getTop() + ")",
                        Math.abs(seamTopBefore - page4Reopened.getTop()) <= 2);

                // Simulate a subtle system-bar inset height change (real WindowInsets padding)
                // and ensure quiet relayout does NOT reset the PDF scroll seam back to top!
                View contentFrame2 = act2.findViewById(R.id.content_frame);
                contentFrame2.setPadding(0, 24, 0, 24);
                layoutReaderAndSettleRelayout(root2, 822, 1480);

                View page4AfterInset = lm2.findViewByPosition(expectedPage + 1);
                assertNotNull(page4AfterInset);
                assertTrue("Inset resize relayout must preserve sub-page PDF seam position",
                        Math.abs(seamTopBefore - page4AfterInset.getTop()) <= 2);
            }
        }
    }

    @Test
    public void switchingPagesToScrollViaToolbarThenExitingMidSeamRestoresExactPosition()
            throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "pages";
        prefs.theme = "sepia";
        prefs.save();

        Book pdf = TestSupport.installFakePdf(app, "Switch To Scroll Seam PDF");
        PdfSession.sourceFactory = (ctx, b) -> PdfRendererSource.fromRasterizer(
                createWideMarginCroppedRasterizer(8));

        Intent intent = new Intent(app, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());

        int seamTopBefore;
        try (ActivityController<ReaderActivity> ctrl =
                     Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
            ReaderActivity act = ctrl.get();
            TestSupport.waitUntil(
                    () -> act.findViewById(R.id.loading).getVisibility() == View.GONE,
                    6000, "open paged");
            View root = act.findViewById(R.id.reader_root);
            layoutReaderAndSettleRelayout(root, 822, 1480);

            // Switch to Scroll View via the real Appearance toolbar button
            MaterialToolbar topBar = act.findViewById(R.id.top_bar);
            topBar.getMenu().performIdentifierAction(R.id.action_appearance, 0);
            Dialog dialog = ShadowDialog.getLatestDialog();
            dialog.findViewById(R.id.btn_scroll).performClick();
            dialog.dismiss();

            layoutReader(root, 822, 1480);
            RecyclerView rv = act.findViewById(R.id.scroll_list);
            waitForVisibleScrollBitmaps(rv, 6000);
            layoutReader(root, 822, 1480);

            // Scroll across pages so we stop right in the middle between Page 2 and Page 3
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            assertNotNull(lm);
            act.goToPage(2);
            layoutReader(root, 822, 1480);
            waitForVisibleScrollBitmaps(rv, 6000);
            layoutReader(root, 822, 1480);

            View page2 = lm.findViewByPosition(2);
            assertNotNull(page2);
            rv.scrollBy(0, Math.round(page2.getHeight() * 0.50f));
            layoutReader(root, 822, 1480);
            waitForVisibleScrollBitmaps(rv, 6000);
            layoutReader(root, 822, 1480);

            View page3 = lm.findViewByPosition(3);
            assertNotNull(page3);
            seamTopBefore = page3.getTop();

            // Exit via toolbar navigation click (just like tapping Back arrow in top bar)
            ctrl.pause().stop();
        }

        // Reopen and verify exact pixel seam between Page 2 and Page 3
        Intent reopen = new Intent(app, ReaderActivity.class);
        reopen.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());
        try (ActivityController<ReaderActivity> ctrl2 =
                     Robolectric.buildActivity(ReaderActivity.class, reopen).setup()) {
            ReaderActivity act2 = ctrl2.get();
            TestSupport.waitUntil(
                    () -> act2.findViewById(R.id.loading).getVisibility() == View.GONE,
                    6000, "reopen scroll");
            View root2 = act2.findViewById(R.id.reader_root);
            layoutReaderAndSettleRelayout(root2, 822, 1480);
            RecyclerView rv2 = act2.findViewById(R.id.scroll_list);
            waitForVisibleScrollBitmaps(rv2, 6000);
            layoutReader(root2, 822, 1480);

            LinearLayoutManager lm2 = (LinearLayoutManager) rv2.getLayoutManager();
            assertNotNull(lm2);
            assertEquals(2, lm2.findFirstVisibleItemPosition());
            View page3Reopened = lm2.findViewByPosition(3);
            assertNotNull(page3Reopened);
            assertTrue("Expected exact seam top ~" + seamTopBefore + " but got "
                            + page3Reopened.getTop(),
                    Math.abs(seamTopBefore - page3Reopened.getTop()) <= 2);

            // Live-toggle Margin trim (Normal -> Off -> Max) while scrolled mid-seam on Page 2
            View page2Normal = lm2.findViewByPosition(2);
            assertNotNull(page2Normal);
            int normalH = page2Normal.getHeight();

            MaterialToolbar topBar2 = act2.findViewById(R.id.top_bar);
            topBar2.getMenu().performIdentifierAction(R.id.action_appearance, 0);
            Dialog trimDialog = ShadowDialog.getLatestDialog();
            trimDialog.findViewById(R.id.btn_trim_off).performClick();
            layoutReaderAndSettleRelayout(root2, 822, 1480);
            waitForVisibleScrollBitmaps(rv2, 6000);
            layoutReader(root2, 822, 1480);
            assertEquals("Switching to Off trim preserves active page",
                    2, lm2.findFirstVisibleItemPosition());
            int offH = lm2.findViewByPosition(2).getHeight();

            trimDialog.findViewById(R.id.btn_trim_max).performClick();
            layoutReaderAndSettleRelayout(root2, 822, 1480);
            waitForVisibleScrollBitmaps(rv2, 6000);
            layoutReader(root2, 822, 1480);
            assertEquals("Switching to Max trim preserves active page",
                    2, lm2.findFirstVisibleItemPosition());
            int maxH = lm2.findViewByPosition(2).getHeight();
            assertTrue("Trim modes produce distinct scroll card heights (off=" + offH
                    + ", normal=" + normalH + ", max=" + maxH + ")",
                    offH != normalH && maxH != offH);
            trimDialog.dismiss();
        }
    }

    private static PdfRendererSource.PageRasterizer createWideMarginCroppedRasterizer(int count) {
        // Raw page is 420x594 (A4 = 1.414), but printed text block sits in [90..330, 72..498]
        // (240x426 => cropped aspect ratio ~ 1.775, hugely taller than 1.414 when fitted to width!)
        return new PdfRendererSource.PageRasterizer() {
            @Override
            public int pageCount() {
                return count;
            }

            @Override
            public int pageWidth(int index) {
                return 420;
            }

            @Override
            public int pageHeight(int index) {
                return 594;
            }

            @Override
            public void rasterize(int index, Bitmap out, Matrix transform) {
                Canvas c = new Canvas(out);
                if (transform != null) c.concat(transform);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFF111827);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                c.drawRect(new RectF(90f, 72f, 330f, 498f), p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(14f);
                c.drawText("Page " + (index + 1), 175f, 104f, p);
                for (int line = 0; line < 14; line++) {
                    c.drawText("Cropped body text line " + (line + 1) + " on page " + (index + 1),
                            102f, 136f + line * 24f, p);
                }
            }

            @Override
            public void close() {
            }
        };
    }

    private static void layoutReader(View root, int w, int h) {
        Robolectric.flushForegroundThreadScheduler();
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Robolectric.flushForegroundThreadScheduler();
    }

    private static void layoutReaderAndSettleRelayout(View root, int w, int h)
            throws InterruptedException {
        layoutReader(root, w, h);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(25);
            Robolectric.flushForegroundThreadScheduler();
        }
        layoutReader(root, w, h);
    }

    private static void waitForVisibleScrollBitmaps(RecyclerView rv, long timeoutMs) {
        TestSupport.waitUntil(() -> {
            Robolectric.flushForegroundThreadScheduler();
            if (rv.getChildCount() == 0) return false;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                if (child instanceof ZoomImageView && !((ZoomImageView) child).hasBitmap()) {
                    return false;
                }
            }
            return true;
        }, timeoutMs, "visible scroll bitmaps rendered");
    }
}
