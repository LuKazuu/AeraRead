package com.aera.read;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.ImageStore;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.ui.AppearanceSheet;
import com.aera.read.ui.MainActivity;
import com.aera.read.ui.PageView;
import com.aera.read.ui.ZoomImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Renders full Native Graphics (SDK 37, Skia + FreeType) visual proof PNGs exercising:
 * 1. Library grid with 3:4 CoverImageView cards and reading progress
 * 2. EPUB inline text formatting + CSS + ruby + inline gaiji & color badges (Light vs Night)
 * 3. PDF automatic outer-margin trimming comparison (Untrimmed v1.0 vs Auto-Trimmed v1.1)
 * 4. PDF Full-Page vs Background-Only theming (Light, Sepia, Night Full, Night Bg) + AppearanceSheet
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class VisualProofTest {

    @Test
    public void renderNativeGraphicsVisualProofSuite() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        File outDir = new File("build/proof-screenshots");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        File libPng = renderLibraryProof(app, new File(outDir, "01-library-grid-v1.1.png"));
        File epubPng = renderEpubInlineProof(app, new File(outDir, "02-epub-inline-light-and-night.png"));
        File cropPng = renderPdfAutoCropProof(app, new File(outDir, "03-pdf-margin-autocrop-comparison.png"));
        File themePng = renderPdfThemesAndSheetProof(app, new File(outDir, "04-pdf-themes-and-appearance.png"));
        File pdfScrollPng = renderPdfScrollAndToolbarSheetProof(
                app, new File(outDir, "05-pdf-scroll-and-viewmode.png"));

        for (File f : new File[]{libPng, epubPng, cropPng, themePng, pdfScrollPng}) {
            assertTrue("Proof screenshot must exist: " + f, f.isFile() && f.length() > 4096);
            Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath());
            assertNotNull("Proof PNG must decode cleanly: " + f, decoded);
            assertTrue(decoded.getWidth() >= 800 && decoded.getHeight() >= 800);
        }
    }

    private static File renderLibraryProof(Application app, File dest) throws Exception {
        TestSupport.clearLibrary(app);
        Book b1 = TestSupport.install(app, "meditations.epub");
        Book b2 = TestSupport.installFakePdf(app, "The Great Gatsby");
        b2.author = "F. Scott Fitzgerald";
        b2.progress = 0.64f;
        b1.progress = 0.32f;
        Library.saveBooks(app, Arrays.asList(b1, b2));

        try (ActivityController<MainActivity> ctrl =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity act = ctrl.get();
            for (int i = 0; i < 15; i++) {
                Thread.sleep(20);
                Robolectric.flushForegroundThreadScheduler();
            }
            View root = act.findViewById(R.id.root);
            Bitmap shot = renderViewToBitmap(root, 822, 1480, 0xFFF8F9FA);
            savePng(shot, dest);
        } finally {
            TestSupport.clearLibrary(app);
        }
        return dest;
    }

    private static File renderEpubInlineProof(Application app, File dest) throws Exception {
        byte[] gaijiPng = createGaijiSealBitmap();
        byte[] badgePng = createColorTagBitmap();
        String dataUri = "data:image/png;base64," + Base64.encodeToString(badgePng, Base64.NO_WRAP);

        String css = ".lead { font-variant: small-caps; font-weight: bold; }\n"
                + ".code { font-family: monospace; }\n";
        String xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><link rel=\"stylesheet\" href=\"style.css\"/></head>"
                + "<body>"
                + "<h2>Chapter I — Inline Engine</h2>"
                + "<p><span class=\"lead\">AERA READ V1.1</span> renders <b>bold</b>, "
                + "<i>italic</i>, <u>underlined</u>, <s>struck</s>, and "
                + "<code>inline(code)</code> seamlessly inside a single flowing paragraph, "
                + "alongside scientific notation like E = mc<sup>2</sup> and H<sub>2</sub>O.</p>"
                + "<p>Inline icons <img src=\"" + dataUri + "\" width=\"34\" height=\"22\"/> "
                + "and East Asian gaiji glyphs <img src=\"gaiji.png\" style=\"width:1.15em;height:1.15em;\"/> "
                + "sit directly on the baseline next to <ruby>漢字<rt>かんじ</rt></ruby> "
                + "and <b><i>nested bold-italic emphasis</i></b> without breaking line flow.</p>"
                + "<blockquote>“Simplicity is prerequisite for reliability.” — <i>Edsger W. Dijkstra</i><sup>[1]</sup></blockquote>"
                + "</body></html>";

        File epubFile = File.createTempFile("proof-epub-", ".epub");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            putUtf8(zos, "mimetype", "application/epub+zip");
            putUtf8(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\"?><container version=\"1.0\" "
                            + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                            + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            putUtf8(zos, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">"
                            + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                            + "<dc:title>Aera Read Inline Showcase</dc:title></metadata>"
                            + "<manifest>"
                            + "<item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                            + "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>"
                            + "<item id=\"g\" href=\"gaiji.png\" media-type=\"image/png\"/>"
                            + "</manifest><spine><itemref idref=\"c1\"/></spine></package>");
            putUtf8(zos, "OEBPS/style.css", css);
            putUtf8(zos, "OEBPS/ch1.xhtml", xhtml);
            zos.putNextEntry(new ZipEntry("OEBPS/gaiji.png"));
            zos.write(gaijiPng);
            zos.closeEntry();
        }

        try (ZipArchive zip = ZipArchive.open(new FileSource(epubFile))) {
            EpubBook book = new EpubParser(zip).parse("Aera Read Inline Showcase");
            ImageStore store = new ImageStore(zip);
            try {
                ReaderPrefs prefs = new ReaderPrefs(app);
                prefs.sizeSp = 19;
                prefs.linePct = 150;
                PagedBook paged = EpubPaginator.paginate(book, TextStyles.create(app, prefs),
                        680, 1080);

                for (EpubBook.Block block : book.chapters.get(0).blocks) {
                    if (block.inlineImages != null) {
                        for (EpubBook.InlineImage im : block.inlineImages) {
                            for (int i = 0; i < 40 && store.request(im.path, 128, () -> {}) == null; i++) {
                                Thread.sleep(10);
                            }
                        }
                    }
                }

                PageView lightView = new PageView(app);
                lightView.setData(paged, paged.page(0), ReaderTheme.LIGHT, store);
                Bitmap lightBmp = renderViewToBitmap(lightView, 680, 1080, ReaderTheme.LIGHT.bg);

                PageView nightView = new PageView(app);
                nightView.setData(paged, paged.page(0), ReaderTheme.NIGHT, store);
                Bitmap nightBmp = renderViewToBitmap(nightView, 680, 1080, ReaderTheme.NIGHT.bg);

                Bitmap combined = composeSideBySide(
                        "EPUB Light Theme (Zero Effects)", lightBmp,
                        "EPUB Night Theme (Auto-Tinted Gaiji)", nightBmp);
                savePng(combined, dest);
            } finally {
                store.close();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epubFile.delete();
        }
        return dest;
    }

    private static File renderPdfAutoCropProof(Application app, File dest) throws Exception {
        PdfRendererSource.PageRasterizer doc = createRealisticWideMarginPdf();

        try (PdfRendererSource src = PdfRendererSource.fromRasterizer(doc)) {
            int panelW = 540;
            int panelH = 880;

            src.setTrimMode(ReaderPrefs.TRIM_OFF);
            Bitmap offPage = src.render(0, panelW);
            ZoomImageView offView = new ZoomImageView(app);
            offView.setTheme(ReaderTheme.LIGHT, true);
            offView.preparePage(0, offPage.getHeight() / (float) offPage.getWidth(),
                    ReaderPrefs.TRIM_OFF);
            offView.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(panelH, View.MeasureSpec.EXACTLY));
            offView.layout(0, 0, panelW, panelH);
            offView.show(0, offPage);
            Bitmap offBmp = renderViewToBitmap(offView, panelW, panelH, 0xFFE5E7EB);

            src.setTrimMode(ReaderPrefs.TRIM_NORMAL);
            Bitmap normPage = src.render(0, panelW);
            ZoomImageView normView = new ZoomImageView(app);
            normView.setTheme(ReaderTheme.LIGHT, true);
            normView.preparePage(0, normPage.getHeight() / (float) normPage.getWidth(),
                    ReaderPrefs.TRIM_NORMAL);
            normView.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(panelH, View.MeasureSpec.EXACTLY));
            normView.layout(0, 0, panelW, panelH);
            normView.show(0, normPage);
            Bitmap normBmp = renderViewToBitmap(normView, panelW, panelH, 0xFFE5E7EB);

            src.setTrimMode(ReaderPrefs.TRIM_MAX);
            Bitmap maxPage = src.render(0, panelW);
            ZoomImageView maxView = new ZoomImageView(app);
            maxView.setTheme(ReaderTheme.LIGHT, true);
            maxView.preparePage(0, maxPage.getHeight() / (float) maxPage.getWidth(),
                    ReaderPrefs.TRIM_MAX);
            maxView.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(panelH, View.MeasureSpec.EXACTLY));
            maxView.layout(0, 0, panelW, panelH);
            maxView.show(0, maxPage);
            Bitmap maxBmp = renderViewToBitmap(maxView, panelW, panelH, 0xFFE5E7EB);

            int pad = 28;
            int headerH = 68;
            int outW = panelW * 3 + pad * 4;
            int outH = panelH + headerH + pad;
            Bitmap combined = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(combined);
            c.drawColor(0xFF0F172A);
            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.WHITE);
            titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titlePaint.setTextSize(22f);
            drawLabeledPanel(c, titlePaint, "1. Off (Original PDF)", offBmp, pad, headerH);
            drawLabeledPanel(c, titlePaint, "2. Normal (Balanced Default)",
                    normBmp, pad * 2 + panelW, headerH);
            drawLabeledPanel(c, titlePaint, "3. Max (Edge-to-Edge Trim)",
                    maxBmp, pad * 3 + panelW * 2, headerH);
            savePng(combined, dest);
        }
        return dest;
    }

    private static File renderPdfThemesAndSheetProof(Application app, File dest) throws Exception {
        Context themed = new ContextThemeWrapper(app, R.style.Theme_Aera);
        PdfRendererSource.PageRasterizer doc = createRealisticWideMarginPdf();
        try (PdfRendererSource src = PdfRendererSource.fromRasterizer(doc)) {
            Bitmap page = src.render(0, 560);

            Bitmap lightPanel = renderPdfPanel(app, page, ReaderTheme.LIGHT, true, 520, 760);
            Bitmap sepiaPanel = renderPdfPanel(app, page, ReaderTheme.SEPIA, true, 520, 760);
            Bitmap nightFullPanel = renderPdfPanel(app, page, ReaderTheme.NIGHT, true, 520, 760);
            Bitmap nightBgPanel = renderPdfPanel(app, page, ReaderTheme.NIGHT, false, 520, 760);

            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.theme = "night";
            prefs.pdfFullTheme = true;
            Bitmap sheetBmp;
            try (ActivityController<MainActivity> actCtrl =
                         Robolectric.buildActivity(MainActivity.class).setup()) {
                AppearanceSheet sheet = new AppearanceSheet(actCtrl.get(), prefs, false, true,
                        new AppearanceSheet.Callback() {
                            @Override
                            public void onAppearanceChanged(boolean needsRelayout) {
                            }

                            @Override
                            public void onAppearancePreview() {
                            }
                        });
                sheet.show();
                Dialog dialog = ShadowDialog.getLatestDialog();
                View sheetRoot = dialog.findViewById(R.id.sheet_root);
                sheetRoot.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int fullH = sheetRoot.getMeasuredHeight();
                sheetRoot.layout(0, 0, 822, fullH);
                sheetBmp = Bitmap.createBitmap(822, fullH, Bitmap.Config.ARGB_8888);
                Canvas sc = new Canvas(sheetBmp);
                sc.drawColor(0xFFF7FAF4);
                sheetRoot.draw(sc);
                dialog.dismiss();
            }
            prefs.theme = "light";
            prefs.save();

            // Compose 2x2 PDF theme grid + Appearance sheet underneath
            int outW = 1140;
            int outH = 1680 + sheetBmp.getHeight() + 90;
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(0xFF0F172A);

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.WHITE);
            titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titlePaint.setTextSize(24f);

            drawLabeledPanel(c, titlePaint, "1. Light (Zero Effects)", lightPanel, 30, 52);
            drawLabeledPanel(c, titlePaint, "2. Sepia (Full Page)", sepiaPanel, 590, 52);
            drawLabeledPanel(c, titlePaint, "3. Night (Full Page — Default)", nightFullPanel, 30, 872);
            drawLabeledPanel(c, titlePaint, "4. Night (Background Only)", nightBgPanel, 590, 872);

            c.drawText("PDF Appearance Sheet (Light / Sepia / Slate / Night + Full Page | Background):",
                    30, 1692, titlePaint);
            c.drawBitmap(sheetBmp, (outW - sheetBmp.getWidth()) / 2f, 1712, null);
            savePng(out, dest);
        }
        return dest;
    }

    private static File renderPdfScrollAndToolbarSheetProof(Application app, File dest)
            throws Exception {
        TestSupport.clearLibrary(app);
        ReaderPrefs init = new ReaderPrefs(app);
        init.theme = "sepia";
        init.pdfFullTheme = true;
        init.viewMode = "pages";
        init.direction = "ltr";
        init.save();

        Book pdf = TestSupport.installFakePdf(app, "The Great Gatsby");
        com.aera.read.reader.PdfSession.sourceFactory = (context, b) ->
                PdfRendererSource.fromRasterizer(new PdfRendererSource.PageRasterizer() {
                    @Override
                    public int pageCount() {
                        return 5;
                    }

                    @Override
                    public int pageWidth(int index) {
                        return 396;
                    }

                    @Override
                    public int pageHeight(int index) {
                        return 520;
                    }

                    @Override
                    public void rasterize(int index, Bitmap out, Matrix transform) {
                        Canvas c = new Canvas(out);
                        if (transform != null) c.concat(transform);
                        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                        p.setColor(0xFF111827);
                        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                        p.setTextSize(15f);
                        c.drawText("CHAPTER " + (index + 1), 152f, 96f, p);
                        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
                        p.setTextSize(9.5f);
                        for (int line = 0; line < 9; line++) {
                            c.drawText("Continuous PDF page " + (index + 1)
                                    + " — seamless scroll flow.", 86f, 128f + line * 18f, p);
                        }
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(index % 2 == 0 ? 0xFF2563EB : 0xFF0D9488);
                        c.drawRoundRect(new RectF(84f, 304f, 312f, 396f), 8f, 8f, p);
                        p.setColor(Color.WHITE);
                        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                        p.setTextSize(12f);
                        c.drawText("PDF Diagram — Page " + (index + 1), 130f, 355f, p);
                        p.setColor(0xFF111827);
                        p.setTextSize(9.5f);
                        c.drawText("— Page " + (index + 1) + " of 5 —", 162f, 434f, p);
                    }

                    @Override
                    public void close() {
                    }
                });

        android.content.Intent intent =
                new android.content.Intent(app, com.aera.read.ui.ReaderActivity.class);
        intent.putExtra(com.aera.read.ui.ReaderActivity.EXTRA_BOOK_ID, pdf.id());

        try (ActivityController<com.aera.read.ui.ReaderActivity> ctrl =
                     Robolectric.buildActivity(com.aera.read.ui.ReaderActivity.class, intent).setup()) {
            com.aera.read.ui.ReaderActivity act = ctrl.get();
            TestSupport.waitUntil(
                    () -> act.findViewById(R.id.loading).getVisibility() == View.GONE,
                    5000, "open pdf");

            // Open Appearance via the REAL toolbar menu action inside ReaderActivity
            com.google.android.material.appbar.MaterialToolbar topBar =
                    act.findViewById(R.id.top_bar);
            topBar.getMenu().performIdentifierAction(R.id.action_appearance, 0);
            Dialog dialog = ShadowDialog.getLatestDialog();
            View sheetRoot = dialog.findViewById(R.id.sheet_root);
            sheetRoot.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int sheetH = sheetRoot.getMeasuredHeight();
            sheetRoot.layout(0, 0, 822, sheetH);
            Bitmap sheetBmp = Bitmap.createBitmap(822, sheetH, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(sheetBmp);
            sc.drawColor(0xFFF7FAF4);
            sheetRoot.draw(sc);

            // Tap "Scroll" right inside the real Appearance sheet!
            dialog.findViewById(R.id.btn_scroll).performClick();
            dialog.dismiss();

            View readerRoot = act.findViewById(R.id.reader_root);
            readerRoot.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1480, View.MeasureSpec.EXACTLY));
            readerRoot.layout(0, 0, 822, 1480);
            for (int i = 0; i < 25; i++) {
                Thread.sleep(20);
                Robolectric.flushForegroundThreadScheduler();
            }
            readerRoot.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1480, View.MeasureSpec.EXACTLY));
            readerRoot.layout(0, 0, 822, 1480);

            // Scroll slightly down so Page 1 -> Page 2 continuous transition is prominently visible
            androidx.recyclerview.widget.RecyclerView rv = act.findViewById(R.id.scroll_list);
            rv.scrollBy(0, 320);
            Robolectric.flushForegroundThreadScheduler();

            Bitmap scrollBmp = Bitmap.createBitmap(822, 1480, Bitmap.Config.ARGB_8888);
            readerRoot.draw(new Canvas(scrollBmp));

            Bitmap combined = composeSideBySide(
                    "Real PDF Toolbar -> Appearance Sheet", sheetBmp,
                    "PDF Continuous Scroll Mode (Page 1 -> Page 2)", scrollBmp);
            savePng(combined, dest);
        } finally {
            com.aera.read.reader.PdfSession.sourceFactory = PdfRendererSource::open;
            init.theme = "light";
            init.viewMode = "pages";
            init.direction = "ltr";
            init.save();
            TestSupport.clearLibrary(app);
        }
        return dest;
    }

    // ------------------------------------------------------------------ helpers

    private static Bitmap renderPdfPanel(Application app, Bitmap page, ReaderTheme theme,
                                         boolean fullPage, int w, int h) {
        ZoomImageView v = new ZoomImageView(app);
        v.setTheme(theme, fullPage);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.show(0, page);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        v.draw(c);
        Paint border = new Paint();
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(0xFF475569);
        c.drawRect(1, 1, w - 1, h - 1, border);
        return out;
    }

    private static void drawLabeledPanel(Canvas c, Paint labelPaint, String label, Bitmap bmp,
                                         int x, int y) {
        c.drawText(label, x, y - 12, labelPaint);
        c.drawBitmap(bmp, x, y, null);
    }

    private static PdfRendererSource.PageRasterizer createRealisticWideMarginPdf() {
        return new PdfRendererSource.PageRasterizer() {
            @Override
            public int pageCount() {
                return 1;
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

                // Chapter header inside [84..312, 118..494] (wide printer margins around it)
                p.setColor(0xFF111827);
                p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                p.setTextSize(15f);
                c.drawText("CHAPTER I", 156f, 136f, p);

                p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
                p.setTextSize(9.6f);
                String[] lines = {
                        "In my younger and more vulnerable years my father",
                        "gave me some advice that I've been turning over in",
                        "my mind ever since.",
                        "\"Whenever you feel like criticizing any one,\" he",
                        "told me, \"just remember that all the people in this",
                        "world haven't had the advantages that you've had.\"",
                        "He didn't say any more, but we've always been",
                        "unusually communicative in a reserved way, and I",
                        "understood that he meant a great deal more than that."
                };
                float y = 162f;
                for (String line : lines) {
                    c.drawText(line, 86f, y, p);
                    y += 14.5f;
                }

                // Figure box with colorful chart bars to demonstrate sharp non-squished diagrams & hue preservation
                RectF card = new RectF(86f, 306f, 310f, 444f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(0xFF334155);
                c.drawRoundRect(card, 6f, 6f, p);

                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2563EB);
                c.drawRect(104f, 368f, 146f, 428f, p);
                p.setColor(0xFF0D9488);
                c.drawRect(162f, 342f, 204f, 428f, p);
                p.setColor(0xFFE11D48);
                c.drawRect(220f, 324f, 262f, 428f, p);

                p.setColor(0xFF111827);
                p.setTextSize(8.5f);
                c.drawText("Fig 1.1 — Vector chart & typography clarity", 104f, 464f, p);
                c.drawText("— 1 —", 184f, 488f, p);
            }

            @Override
            public void close() {
            }
        };
    }

    private static Bitmap composeSideBySide(String leftLabel, Bitmap left,
                                            String rightLabel, Bitmap right) {
        int pad = 28;
        int topHeader = 64;
        int w = pad + left.getWidth() + pad + right.getWidth() + pad;
        int h = topHeader + Math.max(left.getHeight(), right.getHeight()) + pad;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF0F172A);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setTextSize(23f);

        c.drawText(leftLabel, pad, 42, text);
        c.drawBitmap(left, pad, topHeader, null);

        int rightX = pad + left.getWidth() + pad;
        c.drawText(rightLabel, rightX, 42, text);
        c.drawBitmap(right, rightX, topHeader, null);
        return out;
    }

    private static Bitmap renderViewToBitmap(View view, int w, int h, int bg) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(bg);
        view.draw(c);
        return bmp;
    }

    private static byte[] createGaijiSealBitmap() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF141414);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4.5f);
        c.drawRoundRect(new RectF(5, 5, 51, 51), 8f, 8f, p);
        c.drawLine(14, 28, 42, 28, p);
        c.drawLine(28, 12, 28, 44, p);
        c.drawCircle(28, 28, 10f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static byte[] createColorTagBitmap() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(68, 44, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2563EB);
        c.drawRoundRect(new RectF(2, 2, 66, 42), 10f, 10f, p);
        p.setColor(0xFFFBBF24);
        c.drawCircle(22, 22, 9f, p);
        p.setColor(Color.WHITE);
        c.drawRect(36, 17, 56, 27, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static void putUtf8(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void savePng(Bitmap bmp, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
