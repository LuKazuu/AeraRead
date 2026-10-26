package com.aera.read.pdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.reader.PdfSession;
import com.aera.read.ui.AppearanceSheet;
import com.aera.read.ui.ReaderActivity;
import com.aera.read.ui.ZoomImageView;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exhaustive tests for v1.1 PDF automatic outer-margin trimming (sharpness, zero clipping,
 * uniform non-squished aspect ratio, sparse/blank/full-bleed edge cases), Full-Page vs
 * Background-Only PDF theming across Light/Sepia/Slate/Night, AppearanceSheet UI controls,
 * and Android 17 live multi-window resize relayout.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PdfThemeAndAutoCropTest {

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
    }

    @Test
    public void autoCropTrimsWideMarginsWithoutClippingAnyEdgeOrSquishingAspectRatio()
            throws Exception {
        // Real gatsby.pdf MediaBox = 396x612 with ~22% empty outer margins on every side:
        // Content box sits inside x=[88..308], y=[132..480] (width=220, height=348).
        PdfRendererSource.PageRasterizer wideMarginDoc = new PdfRendererSource.PageRasterizer() {
            @Override
            public int pageCount() {
                return 4;
            }

            @Override
            public int pageWidth(int index) {
                return 396;
            }

            @Override
            public int pageHeight(int index) {
                return 612;
            }

            @Override
            public void rasterize(int index, Bitmap dest, Matrix transform) {
                Canvas c = new Canvas(dest);
                if (transform != null) c.concat(transform);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                if (index == 0) {
                    // Wide-margin page with 4 distinct corner sentinels + exact 100x100 square + body text
                    p.setColor(0xFFE11D48); // top-left crimson sentinel at (88, 132)
                    c.drawRect(88, 132, 94, 138, p);
                    p.setColor(0xFF0284C7); // top-right cyan-blue sentinel at (302..308, 132..138)
                    c.drawRect(302, 132, 308, 138, p);
                    p.setColor(0xFF16A34A); // bottom-left green sentinel at (88..94, 474..480)
                    c.drawRect(88, 474, 94, 480, p);
                    p.setColor(0xFFD97706); // bottom-right amber sentinel at (302..308, 474..480)
                    c.drawRect(302, 474, 308, 480, p);

                    // Exact 100x100 square in PDF coordinate space to verify zero squishing
                    p.setColor(0xFF4F46E5);
                    c.drawRect(148, 170, 248, 270, p);

                    // Crisp serif body text lines inside [92..304]
                    p.setColor(0xFF141414);
                    p.setTypeface(Typeface.SERIF);
                    p.setTextSize(10.5f);
                    for (int line = 0; line < 11; line++) {
                        c.drawText("In my younger and more vulnerable years my father",
                                92f, 296f + line * 15f, p);
                    }
                } else if (index == 1) {
                    // Completely blank white page -> must never crash or divide by zero
                } else if (index == 2) {
                    // Sparse off-center footer page number at bottom-right (295, 555)
                    p.setColor(0xFF222222);
                    p.setTextSize(10f);
                    c.drawRect(295, 550, 312, 558, p);
                } else if (index == 3) {
                    // Full-bleed cover artwork filling [0..396, 0..612]
                    p.setColor(0xFF1E3A8A);
                    c.drawRect(0, 0, 396, 612, p);
                }
            }

            @Override
            public void close() {
            }
        };

        try (PdfRendererSource trimmedSource = PdfRendererSource.fromRasterizer(wideMarginDoc)) {
            Bitmap trimmed = trimmedSource.render(0, 900);
            assertNotNull(trimmed);
            assertEquals(900, trimmed.getWidth());

            // Render untrimmed baseline at the same 900px width for direct comparison
            int untrimmedH = Math.round(900f * 612f / 396f);
            Bitmap untrimmed = Bitmap.createBitmap(900, untrimmedH, Bitmap.Config.ARGB_8888);
            untrimmed.eraseColor(Color.WHITE);
            Matrix rawScale = new Matrix();
            float s = 900f / 396f;
            rawScale.setScale(s, s);
            wideMarginDoc.rasterize(0, untrimmed, rawScale);

            int rawInk = countDarkPixels(untrimmed, 120);
            int trimmedInk = countDarkPixels(trimmed, 120);
            assertTrue("Auto-trimmed PDF must render >2.0x larger glyph ink area than raw page ("
                    + trimmedInk + " vs " + rawInk + ")", trimmedInk > rawInk * 2.0);

            // ZERO clipping: all 4 extreme corner sentinels must appear inside trimmed output
            // AND none may touch the 0th or last pixel row/column (clean safety padding preserved)
            assertSentinelPresentWithMargin(trimmed, 0xFFE11D48, "top-left");
            assertSentinelPresentWithMargin(trimmed, 0xFF0284C7, "top-right");
            assertSentinelPresentWithMargin(trimmed, 0xFF16A34A, "bottom-left");
            assertSentinelPresentWithMargin(trimmed, 0xFFD97706, "bottom-right");

            // ZERO squishing: the 100x100 indigo square must stay 1:1 square in rendered pixels
            int[] box = boundingBoxOfColor(trimmed, 0xFF4F46E5, 35);
            int sqW = box[2] - box[0] + 1;
            int sqH = box[3] - box[1] + 1;
            int rawSqW = Math.round(900f * 100f / 396f); // 227px without margin trim
            assertTrue("Square must be >1.4x wider after margin trim (" + sqW
                    + " vs " + rawSqW + ")", sqW > rawSqW * 1.4f);
            // Verify all 3 Margin trim modes (Off < Normal < Max) on the exact same source!
            trimmedSource.setTrimMode(ReaderPrefs.TRIM_OFF);
            Bitmap offBmp = trimmedSource.render(0, 900);
            assertNotNull(offBmp);
            assertEquals(untrimmedH, offBmp.getHeight());
            int[] offBox = boundingBoxOfColor(offBmp, 0xFF4F46E5, 35);
            int offSqW = offBox[2] - offBox[0] + 1;
            assertTrue("Off mode preserves original PDF scale", Math.abs(offSqW - rawSqW) <= 3);

            trimmedSource.setTrimMode(ReaderPrefs.TRIM_MAX);
            Bitmap maxBmp = trimmedSource.render(0, 900);
            assertNotNull(maxBmp);
            assertSentinelPresentWithMargin(maxBmp, 0xFFE11D48, "max top-left");
            assertSentinelPresentWithMargin(maxBmp, 0xFF0284C7, "max top-right");
            assertSentinelPresentWithMargin(maxBmp, 0xFF16A34A, "max bottom-left");
            assertSentinelPresentWithMargin(maxBmp, 0xFFD97706, "max bottom-right");
            int[] maxBox = boundingBoxOfColor(maxBmp, 0xFF4F46E5, 35);
            int maxSqW = maxBox[2] - maxBox[0] + 1;
            int maxSqH = maxBox[3] - maxBox[1] + 1;
            assertTrue("Max trim must magnify content wider than Normal ("
                    + maxSqW + " > " + sqW + " > " + offSqW + ")",
                    maxSqW > sqW && sqW > offSqW);
            assertTrue("Max trim keeps 1:1 uniform aspect ratio without squishing",
                    Math.abs(maxSqW - maxSqH) <= 3);

            trimmedSource.setTrimMode(ReaderPrefs.TRIM_NORMAL);

            // Blank page edge case
            Bitmap blank = trimmedSource.render(1, 600);
            assertNotNull(blank);
            assertEquals(Color.WHITE, blank.getPixel(300, 300));

            // Sparse off-center footer edge case: footer ink is preserved (never clipped)
            // AND MIN_CROP_SPAN prevented blowing a 17px mark up to 600px wide!
            Bitmap sparse = trimmedSource.render(2, 600);
            int sparseInk = countDarkPixels(sparse, 120);
            assertTrue("Off-center sparse footer must not be clipped", sparseInk > 40);
            assertTrue("Sparse footer must not be over-magnified across whole screen: " + sparseInk,
                    sparseInk < 8000);

            // Full-bleed cover edge case: fills entire bitmap cleanly
            Bitmap bleed = trimmedSource.render(3, 600);
            assertEquals(0xFF1E3A8A, bleed.getPixel(4, 4));
            assertEquals(0xFF1E3A8A, bleed.getPixel(bleed.getWidth() - 5, bleed.getHeight() - 5));
        }
    }

    @Test
    public void fullPageVsBackgroundOnlyPdfThemesRenderAccuratelyAcrossAllFourThemes() {
        Application app = ApplicationProvider.getApplicationContext();

        // Synthetic crisp PDF page: white paper, dark text bar, vivid blue & crimson chart bars
        Bitmap pageBmp = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888);
        Canvas pc = new Canvas(pageBmp);
        pc.drawColor(Color.WHITE);
        Paint p = new Paint();
        p.setColor(0xFF141414);
        pc.drawRect(60, 80, 340, 130, p); // black text block
        p.setColor(0xFF2563EB);
        pc.drawRect(60, 200, 190, 280, p); // blue bar
        p.setColor(0xFFE11D48);
        pc.drawRect(210, 200, 340, 280, p); // crimson bar

        // Viewport 600x600 so left/right letterbox bars [0..90] are outside the fitted page!
        ZoomImageView view = new ZoomImageView(app);
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 600, 600);
        view.show(0, pageBmp);

        // 1. LIGHT Full Page: zero visual effects -> exact white, exact black, exact blue
        view.setTheme(ReaderTheme.LIGHT, true);
        Bitmap lightOut = drawZoomImage(view, 600, 600);
        assertEquals(0xFFFFFFFF, lightOut.getPixel(10, 300)); // outer background
        assertEquals(0xFFFFFFFF, lightOut.getPixel(300, 30)); // page paper
        assertEquals(0xFF141414, lightOut.getPixel(200, 110)); // black ink untouched
        assertEquals(0xFF2563EB, lightOut.getPixel(160, 250)); // blue bar untouched

        // 2. SEPIA Full Page: warm sepia paper & warm sepia ink
        view.setTheme(ReaderTheme.SEPIA, true);
        Bitmap sepiaOut = drawZoomImage(view, 600, 600);
        assertEquals(ReaderTheme.SEPIA.bg, sepiaOut.getPixel(10, 300));
        int sepiaPaper = sepiaOut.getPixel(300, 30);
        assertTrue("Sepia full-page paper must match warm sepia tone",
                Color.red(sepiaPaper) > Color.blue(sepiaPaper) + 12);

        // 3. NIGHT Full Page: dark page background, light text ink, AND preserved color hues!
        view.setTheme(ReaderTheme.NIGHT, true);
        Bitmap nightFull = drawZoomImage(view, 600, 600);
        assertEquals(ReaderTheme.NIGHT.bg, nightFull.getPixel(10, 300));
        int nightPaper = nightFull.getPixel(300, 30);
        assertTrue("Night full-page paper must be dark, not blinding white",
                Color.red(nightPaper) < 35 && Color.green(nightPaper) < 35 && Color.blue(nightPaper) < 35);
        int nightInk = nightFull.getPixel(200, 110);
        assertTrue("Night full-page text must invert to crisp light ink: "
                        + Integer.toHexString(nightInk),
                Color.red(nightInk) > 175 && Color.green(nightInk) > 175 && Color.blue(nightInk) > 175);
        int nightBlue = nightFull.getPixel(160, 250);
        int nightRed = nightFull.getPixel(310, 250);
        assertTrue("Hue-preserving Night filter must keep blue bar blue-dominant",
                Color.blue(nightBlue) > Color.red(nightBlue) + 25);
        assertTrue("Hue-preserving Night filter must keep crimson bar red-dominant",
                Color.red(nightRed) > Color.blue(nightRed) + 25);

        // 4. NIGHT Background-Only (pdfFullTheme = false): dark frame, untouched white page!
        view.setTheme(ReaderTheme.NIGHT, false);
        Bitmap nightBgOnly = drawZoomImage(view, 600, 600);
        assertEquals("Outer letterbox must still be Night dark",
                ReaderTheme.NIGHT.bg, nightBgOnly.getPixel(10, 300));
        assertEquals("PDF page paper must remain untouched white in Background-Only mode",
                0xFFFFFFFF, nightBgOnly.getPixel(300, 30));
        assertEquals("PDF page ink must remain untouched black in Background-Only mode",
                0xFF141414, nightBgOnly.getPixel(200, 110));
    }

    @Test
    public void appearanceSheetShowsPdfThemeToggleOnlyForPdfsAndUpdatesLive() {
        Application app = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(app, R.style.Theme_Aera);
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.theme = "night";
        prefs.pdfFullTheme = true;
        prefs.save();

        AtomicBoolean notified = new AtomicBoolean(false);
        AppearanceSheet.Callback cb = new AppearanceSheet.Callback() {
            @Override
            public void onAppearanceChanged(boolean needsRelayout) {
                notified.set(true);
            }

            @Override
            public void onAppearancePreview() {
            }
        };

        // PDF mode: supportsTypography = false
        AppearanceSheet pdfSheet = new AppearanceSheet(themed, prefs, false, true, cb);
        pdfSheet.show();
        Dialog pdfDialog = ShadowDialog.getLatestDialog();
        assertNotNull(pdfDialog);
        View pdfSection = pdfDialog.findViewById(R.id.pdf_theme_section);
        MaterialButtonToggleGroup pdfToggle = pdfDialog.findViewById(R.id.pdf_theme_toggle);
        assertNotNull(pdfSection);
        assertNotNull(pdfToggle);
        assertEquals(View.VISIBLE, pdfSection.getVisibility());
        assertEquals(R.id.btn_pdf_full, pdfToggle.getCheckedButtonId());
        com.google.android.material.button.MaterialButton bFull =
                pdfDialog.findViewById(R.id.btn_pdf_full);
        com.google.android.material.button.MaterialButton bBg =
                pdfDialog.findViewById(R.id.btn_pdf_bg);
        View root = pdfDialog.findViewById(R.id.sheet_root);
        root.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, 822, root.getMeasuredHeight());
        assertButtonRendersVisibleText(bFull, "Full page");
        assertButtonRendersVisibleText(bBg, "Background");

        // Verify Margin trim toggle (Off | Normal | Max) defaults to Normal and renders crisp text
        MaterialButtonToggleGroup trimToggle = pdfDialog.findViewById(R.id.pdf_trim_toggle);
        assertNotNull(trimToggle);
        assertEquals(R.id.btn_trim_normal, trimToggle.getCheckedButtonId());
        com.google.android.material.button.MaterialButton bOff =
                pdfDialog.findViewById(R.id.btn_trim_off);
        com.google.android.material.button.MaterialButton bNormal =
                pdfDialog.findViewById(R.id.btn_trim_normal);
        com.google.android.material.button.MaterialButton bMax =
                pdfDialog.findViewById(R.id.btn_trim_max);
        assertButtonRendersVisibleText(bOff, "Off");
        assertButtonRendersVisibleText(bNormal, "Normal");
        assertButtonRendersVisibleText(bMax, "Max");

        bMax.performClick();
        assertEquals(ReaderPrefs.TRIM_MAX, new ReaderPrefs(app).pdfTrim);
        bOff.performClick();
        assertEquals(ReaderPrefs.TRIM_OFF, new ReaderPrefs(app).pdfTrim);
        bNormal.performClick();
        assertEquals(ReaderPrefs.TRIM_NORMAL, new ReaderPrefs(app).pdfTrim);

        // Tap "Background" button
        pdfDialog.findViewById(R.id.btn_pdf_bg).performClick();
        assertFalse(prefs.pdfFullTheme);
        assertTrue(notified.get());
        assertFalse("Updated setting must persist immediately", new ReaderPrefs(app).pdfFullTheme);
        pdfDialog.dismiss();

        // EPUB mode: supportsTypography = true -> PDF theme section is hidden
        AppearanceSheet epubSheet = new AppearanceSheet(themed, prefs, true, true, cb);
        epubSheet.show();
        Dialog epubDialog = ShadowDialog.getLatestDialog();
        View hiddenSection = epubDialog.findViewById(R.id.pdf_theme_section);
        assertNotNull(hiddenSection);
        assertEquals(View.GONE, hiddenSection.getVisibility());
        epubDialog.dismiss();

        prefs.theme = "light";
        prefs.pdfFullTheme = true;
        prefs.save();
    }

    @Test
    public void pdfReaderActivityExposesViewModeAndPageTurnInToolbarAppearanceSheet()
            throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        ReaderPrefs init = new ReaderPrefs(app);
        init.viewMode = "pages";
        init.direction = "ltr";
        init.theme = "light";
        init.save();

        Book pdf = TestSupport.installFakePdf(app, "Multi-Mode PDF");
        PdfSession.sourceFactory = (context, b) -> new com.aera.read.TestPdfSource(12);

        Intent intent = new Intent(app, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, pdf.id());

        try (ActivityController<ReaderActivity> ctrl =
                     Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
            ReaderActivity activity = ctrl.get();
            TestSupport.waitUntil(
                    () -> activity.findViewById(R.id.loading).getVisibility() == View.GONE,
                    5000, "pdf open");

            View content = activity.findViewById(R.id.content_frame);
            content.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, 822, 1600);

            // Open Appearance via the real toolbar menu item (exact user flow in compiled APK)
            com.google.android.material.appbar.MaterialToolbar topBar =
                    activity.findViewById(R.id.top_bar);
            topBar.getMenu().performIdentifierAction(R.id.action_appearance, 0);
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull("Appearance dialog must open from PDF toolbar", dialog);

            assertEquals("PDF theme scope section visible",
                    View.VISIBLE, dialog.findViewById(R.id.pdf_theme_section).getVisibility());
            assertEquals("View mode section MUST be visible for PDFs",
                    View.VISIBLE, dialog.findViewById(R.id.layout_section).getVisibility());
            assertEquals("Page turn direction row MUST be visible in paged PDF mode",
                    View.VISIBLE, dialog.findViewById(R.id.direction_row).getVisibility());
            assertEquals("EPUB typography section stays hidden for PDFs",
                    View.GONE, dialog.findViewById(R.id.typography_section).getVisibility());

            // Switch PDF page turn direction to R/L live
            dialog.findViewById(R.id.btn_rtl).performClick();
            View pager = activity.findViewById(R.id.pager);
            assertEquals(View.LAYOUT_DIRECTION_RTL, pager.getLayoutDirection());

            // Switch PDF view mode to Scroll live
            dialog.findViewById(R.id.btn_scroll).performClick();
            assertEquals("Direction row hides while Scroll mode is selected",
                    View.GONE, dialog.findViewById(R.id.direction_row).getVisibility());
            assertEquals(View.GONE, pager.getVisibility());
            View scrollList = activity.findViewById(R.id.scroll_list);
            assertEquals(View.VISIBLE, scrollList.getVisibility());

            scrollList.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            scrollList.layout(0, 0, 822, 1600);
            androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) scrollList;
            assertTrue("Scroll list must mount PDF page views", rv.getChildCount() >= 1);
            assertTrue(rv.getChildAt(0) instanceof ZoomImageView);

            // Switch Margin trim live inside ReaderActivity: Normal -> Off -> Max -> Normal
            MaterialButtonToggleGroup trimToggle = dialog.findViewById(R.id.pdf_trim_toggle);
            assertNotNull(trimToggle);
            dialog.findViewById(R.id.btn_trim_off).performClick();
            assertEquals(ReaderPrefs.TRIM_OFF, new ReaderPrefs(app).pdfTrim);
            dialog.findViewById(R.id.btn_trim_max).performClick();
            assertEquals(ReaderPrefs.TRIM_MAX, new ReaderPrefs(app).pdfTrim);
            dialog.findViewById(R.id.btn_trim_normal).performClick();
            assertEquals(ReaderPrefs.TRIM_NORMAL, new ReaderPrefs(app).pdfTrim);

            dialog.dismiss();
        } finally {
            init.viewMode = "pages";
            init.direction = "ltr";
            init.save();
            TestSupport.clearLibrary(app);
        }
    }

    @Test
    public void readerActivityRelayoutsCleanlyOnAndroid17MultiWindowResize() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book epub = TestSupport.install(app, "meditations.epub");

        Intent intent = new Intent(app, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, epub.id());

        try (ActivityController<ReaderActivity> ctrl =
                     Robolectric.buildActivity(ReaderActivity.class, intent).setup()) {
            ReaderActivity activity = ctrl.get();
            View content = activity.findViewById(R.id.content_frame);
            assertNotNull(content);

            TestSupport.waitUntil(() -> activity.findViewById(R.id.loading).getVisibility() == View.GONE,
                    5000, "initial open");

            // Simulate phone layout -> foldable/tablet multi-window resize
            content.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, 800, 1400);
            Robolectric.flushForegroundThreadScheduler();
            int phonePages = currentTotalPages(activity);
            assertTrue(phonePages > 5);

            content.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, 1400, 1400);
            TestSupport.waitUntil(() -> {
                try {
                    return currentTotalPages(activity) < phonePages;
                } catch (Exception e) {
                    return false;
                }
            }, 5000, "multi-window relayout");

            int tabletPages = currentTotalPages(activity);
            assertTrue("Wider multi-window viewport must reflow to fewer pages ("
                    + tabletPages + " vs " + phonePages + ")", tabletPages < phonePages);
        } finally {
            TestSupport.clearLibrary(app);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int currentTotalPages(ReaderActivity activity) throws Exception {
        java.lang.reflect.Field f = ReaderActivity.class.getDeclaredField("session");
        f.setAccessible(true);
        com.aera.read.reader.ReadingSession s = (com.aera.read.reader.ReadingSession) f.get(activity);
        return s.pageCount();
    }

    private static void assertButtonRendersVisibleText(View button, String label) {
        Bitmap bmp = Bitmap.createBitmap(button.getWidth(), button.getHeight(),
                Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        button.draw(new Canvas(bmp));
        int ink = countDarkPixels(bmp, 100);
        assertTrue("Button '" + label + "' must render visible centered text ink on Android 17 "
                + "(got " + ink + " dark px)", ink > 200);
    }

    private static Bitmap drawZoomImage(ZoomImageView view, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        view.draw(canvas);
        return out;
    }

    private static int countDarkPixels(Bitmap bmp, int maxLum) {
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                int lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
                if (lum <= maxLum) count++;
            }
        }
        return count;
    }

    private static void assertSentinelPresentWithMargin(Bitmap bmp, int targetColor, String label) {
        int[] box = boundingBoxOfColor(bmp, targetColor, 45);
        assertTrue("Missing " + label + " sentinel — outer content was clipped!", box[0] >= 0);
        assertTrue(label + " touched left edge (x=" + box[0] + ")", box[0] >= 3);
        assertTrue(label + " touched top edge (y=" + box[1] + ")", box[1] >= 3);
        assertTrue(label + " touched right edge (x=" + box[2] + ")", box[2] <= bmp.getWidth() - 4);
        assertTrue(label + " touched bottom edge (y=" + box[3] + ")", box[3] <= bmp.getHeight() - 4);
    }

    private static int[] boundingBoxOfColor(Bitmap bmp, int color, int tolerance) {
        int tr = Color.red(color), tg = Color.green(color), tb = Color.blue(color);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = -1, maxY = -1;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                if (Math.abs(Color.red(c) - tr) <= tolerance
                        && Math.abs(Color.green(c) - tg) <= tolerance
                        && Math.abs(Color.blue(c) - tb) <= tolerance) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return new int[]{-1, -1, -1, -1};
        return new int[]{minX, minY, maxX, maxY};
    }
}
