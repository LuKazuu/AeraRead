package com.aera.read.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.ui.PageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exhaustive tests for EPUB inline text formatting (HTML tags, nested combinations, CSS rules,
 * inline styles, display:none, small-caps, sup/sub) and inline images / gaiji (archive images,
 * data URIs, em/px sizing, alt fallback, CJK flow, monochrome dark tinting vs Light zero-effects).
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class EpubInlineFormattingAndGaijiTest {

    @Test
    public void parsesAllInlineHtmlTagsNestedCombinationsCssRulesAndDisplayNoneInSingleBlock()
            throws Exception {
        String css = ".sc { font-variant: small-caps; text-decoration: underline; }\n"
                + "#warn { font-weight: 700; font-style: italic; }\n"
                + "span.strike { text-decoration: line-through; }\n"
                + ".mono { font-family: 'Courier New', monospace; }\n"
                + ".secret { display: none; }\n";

        String xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                + "<head><link rel=\"stylesheet\" href=\"styles/book.css\"/>\n"
                + "<style>.note { font-size: 80%; }</style></head>\n"
                + "<body><p>"
                + "Plain <b>bold</b> <i>italic</i> <u>under</u> <s>struck</s> "
                + "<code>code</code> H<sub>2</sub>O E=mc<sup>2</sup> "
                + "<small>tiny</small> "
                + "<b><i><u>triple</u></i></b> "
                + "<span class=\"sc\">chapter</span> "
                + "<span id=\"warn\">alert</span> "
                + "<span class=\"strike mono\">oldmono</span> "
                + "<span class=\"note\" style=\"font-weight:bold;\">cssnote</span>"
                + "<span class=\"secret\">DO_NOT_SHOW</span>"
                + "</p></body></html>";

        Map<String, byte[]> extra = new LinkedHashMap<>();
        extra.put("OEBPS/styles/book.css", css.getBytes(StandardCharsets.UTF_8));
        EpubBook book = openSyntheticEpub(xhtml, extra);

        assertEquals(1, book.chapters.size());
        List<EpubBook.Block> blocks = book.chapters.get(0).blocks;
        assertEquals("Entire inline paragraph must stay a single TEXT block", 1, blocks.size());

        EpubBook.Block b = blocks.get(0);
        assertEquals(EpubBook.Block.TEXT, b.type);
        assertEquals(EpubBook.Block.STYLE_BODY, b.style);
        assertFalse("display:none text must be excluded", b.text.contains("DO_NOT_SHOW"));
        assertNotNull(b.spans);

        assertEquals(EpubBook.InlineSpan.BOLD, styleAtWord(b, "bold"));
        assertEquals(EpubBook.InlineSpan.ITALIC, styleAtWord(b, "italic"));
        assertEquals(EpubBook.InlineSpan.UNDERLINE, styleAtWord(b, "under"));
        assertEquals(EpubBook.InlineSpan.STRIKE, styleAtWord(b, "struck"));
        assertEquals(EpubBook.InlineSpan.CODE, styleAtWord(b, "code"));
        assertEquals(EpubBook.InlineSpan.SUB, styleAtWord(b, "2"));
        assertEquals(EpubBook.InlineSpan.SUP, styleAtLastWord(b, "2"));
        assertEquals(EpubBook.InlineSpan.SMALL, styleAtWord(b, "tiny"));
        assertEquals(EpubBook.InlineSpan.BOLD | EpubBook.InlineSpan.ITALIC
                | EpubBook.InlineSpan.UNDERLINE, styleAtWord(b, "triple"));
        assertEquals(EpubBook.InlineSpan.SMALL | EpubBook.InlineSpan.UNDERLINE,
                styleAtWord(b, "chapter"));
        assertEquals(EpubBook.InlineSpan.BOLD | EpubBook.InlineSpan.ITALIC,
                styleAtWord(b, "alert"));
        assertEquals(EpubBook.InlineSpan.STRIKE | EpubBook.InlineSpan.CODE,
                styleAtWord(b, "oldmono"));
        assertEquals(EpubBook.InlineSpan.SMALL | EpubBook.InlineSpan.BOLD,
                styleAtWord(b, "cssnote"));
    }

    @Test
    public void nativeGraphicsRendersInlineStylesWithoutSaveLayerOrTightLineClipping()
            throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        String plainXhtml = wrapBody("<p>ReferenceWord ReferenceWord ReferenceWord</p>");
        String boldXhtml = wrapBody("<p><b>ReferenceWord ReferenceWord ReferenceWord</b></p>");
        String richXhtml = wrapBody("<p>Top<sup>[99]</sup> <b>Bold</b> <i>Italic</i> "
                + "<u>Underline</u> <s>Strike</s> <code>Mono()</code> "
                + "<span style=\"font-variant: small-caps\">SmallCaps</span> "
                + "Sub<sub>base</sub></p>");

        Bitmap plainBmp = renderFirstPage(app, openSyntheticEpub(plainXhtml, null),
                ReaderTheme.LIGHT, 155, false);
        Bitmap boldBmp = renderFirstPage(app, openSyntheticEpub(boldXhtml, null),
                ReaderTheme.LIGHT, 155, false);

        int plainInk = countNonBgPixels(plainBmp, ReaderTheme.LIGHT.bg);
        int boldInk = countNonBgPixels(boldBmp, ReaderTheme.LIGHT.bg);
        assertTrue("Bold text must paint measurably more ink pixels than plain text ("
                + boldInk + " vs " + plainInk + ")", boldInk > plainInk);

        // Tight 125% leading with superscript on very first line + forbidSaveLayer = true
        Bitmap tightBmp = renderFirstPage(app, openSyntheticEpub(richXhtml, null),
                ReaderTheme.LIGHT, 125, true);
        assertTrue("Rich inline page must render visible glyphs",
                countNonBgPixels(tightBmp, ReaderTheme.LIGHT.bg) > 400);
        // Top padding row must stay clean (superscript expanded line ascent instead of clipping)
        for (int x = 0; x < tightBmp.getWidth(); x++) {
            assertEquals("Top edge must not clip superscript ink",
                    ReaderTheme.LIGHT.bg, tightBmp.getPixel(x, 0));
        }
    }

    @Test
    public void inlineImagesGaijiDataUrisAltFallbackAndStandaloneImagesBehaveCorrectly()
            throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        byte[] gaijiPng = makeMonochromeGlyphPng(48, 48);
        byte[] badgePng = makeColorBadgePng(64, 48);
        byte[] platePng = makeColorBadgePng(320, 200);
        String dataUri = "data:image/png;base64," + Base64.encodeToString(badgePng, Base64.NO_WRAP);

        String xhtml = wrapBody(
                "<p>外字<img src=\"images/gaiji.png\" style=\"width:1.1em;height:1.1em;\"/>テストと"
                        + "カラー<img src=\"" + dataUri + "\" width=\"28\" height=\"21\"/>バッジと"
                        + "欠損<img src=\"images/missing.png\" alt=\"[欠字]\"/>"
                        + "<img src=\"images/missing2.png\" alt=\"\"/>終。</p>"
                        + "<p><img src=\"images/plate.png\" alt=\"Standalone Plate\"/></p>");

        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("OEBPS/images/gaiji.png", gaijiPng);
        assets.put("OEBPS/images/plate.png", platePng);

        File epubFile = writeSyntheticEpubFile(xhtml, assets);
        try (ZipArchive zip = ZipArchive.open(new FileSource(epubFile))) {
            EpubBook book = new EpubParser(zip).parse("Inline Fixture");
            List<EpubBook.Block> blocks = book.chapters.get(0).blocks;
            assertEquals("Paragraph with inline images + standalone figure = 2 blocks",
                    2, blocks.size());

            EpubBook.Block inlineBlock = blocks.get(0);
            assertEquals(EpubBook.Block.TEXT, inlineBlock.type);
            assertNotNull(inlineBlock.inlineImages);
            assertEquals("2 valid inline images (missing ones fell back to alt/empty)",
                    2, inlineBlock.inlineImages.size());
            assertTrue("Missing image with alt text must insert alt text inline",
                    inlineBlock.text.contains("[欠字]"));
            assertEquals("Standalone figure must stay Block.IMAGE",
                    EpubBook.Block.IMAGE, blocks.get(1).type);

            ImageStore images = new ImageStore(zip);
            try {
                ReaderPrefs prefs = new ReaderPrefs(app);
                prefs.sizeSp = 20;
                prefs.linePct = 130;
                PagedBook paged = EpubPaginator.paginate(book, TextStyles.create(app, prefs),
                        720, 1000);

                // Warm async cache for both inline images
                for (EpubBook.InlineImage im : inlineBlock.inlineImages) {
                    Bitmap b = images.request(im.path, 128, () -> {});
                    for (int tries = 0; b == null && tries < 50; tries++) {
                        Thread.sleep(10);
                        b = images.request(im.path, 128, () -> {});
                    }
                    assertNotNull("Inline image must decode: " + im.path, b);
                }

                // Render in LIGHT: zero effects -> badge keeps vivid red/blue & gaiji stays dark
                PageView view = new PageView(app);
                view.setData(paged, paged.page(0), ReaderTheme.LIGHT, images);
                Bitmap lightBmp = drawView(view, 720, 1000, true);
                assertTrue("Light render must contain colorful inline badge pixels",
                        countColorfulPixels(lightBmp) > 30);

                // Render in NIGHT: monochrome gaiji tints light on dark bg AND colorful badge stays colorful!
                view.setData(paged, paged.page(0), ReaderTheme.NIGHT, images);
                Bitmap nightBmp = drawView(view, 720, 1000, true);
                assertTrue("Night render must preserve colorful inline badge pixels",
                        countColorfulPixels(nightBmp) > 30);
                assertTrue("Night render must paint light glyph & tinted gaiji pixels",
                        countBrightPixels(nightBmp, 160) > 200);

                // Live reflow to larger font size preserves exact character anchor across \uFFFC
                int anchorOffset = inlineBlock.text.indexOf("バッジ");
                prefs.sizeSp = 28;
                PagedBook reflowed = EpubPaginator.paginate(book, TextStyles.create(app, prefs),
                        720, 1000);
                int targetPage = reflowed.pageIndex(0, anchorOffset);
                PagedBook.Page landed = reflowed.page(targetPage);
                assertTrue(anchorOffset >= landed.startOffset);
            } finally {
                images.close();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epubFile.delete();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int styleAtWord(EpubBook.Block block, String word) {
        int idx = block.text.indexOf(word);
        assertTrue("Expected word '" + word + "' in: " + block.text, idx >= 0);
        return styleAt(block, idx);
    }

    private static int styleAtLastWord(EpubBook.Block block, String word) {
        int idx = block.text.lastIndexOf(word);
        assertTrue("Expected word '" + word + "' in: " + block.text, idx >= 0);
        return styleAt(block, idx);
    }

    private static int styleAt(EpubBook.Block block, int offset) {
        int mask = 0;
        if (block.spans != null) {
            for (EpubBook.InlineSpan s : block.spans) {
                if (offset >= s.start && offset < s.end) {
                    mask |= (s.mask & 0xFF);
                }
            }
        }
        return mask;
    }

    private static String wrapBody(String bodyInner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                + bodyInner + "</body></html>";
    }

    private static EpubBook openSyntheticEpub(String chapterXhtml, Map<String, byte[]> extraEntries)
            throws Exception {
        File f = writeSyntheticEpubFile(chapterXhtml, extraEntries);
        try (ZipArchive zip = ZipArchive.open(new FileSource(f))) {
            return new EpubParser(zip).parse("Inline Fixture");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static File writeSyntheticEpubFile(String chapterXhtml, Map<String, byte[]> extraEntries)
            throws Exception {
        File file = File.createTempFile("inline-test-", ".epub");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            putUtf8(zos, "mimetype", "application/epub+zip");
            putUtf8(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\"?>"
                            + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                            + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            StringBuilder manifest = new StringBuilder();
            manifest.append("<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>");
            if (extraEntries != null) {
                int i = 0;
                for (String key : extraEntries.keySet()) {
                    String rel = key.startsWith("OEBPS/") ? key.substring(6) : key;
                    String media = rel.endsWith(".css") ? "text/css" : "image/png";
                    manifest.append("<item id=\"x").append(++i).append("\" href=\"")
                            .append(rel).append("\" media-type=\"").append(media).append("\"/>");
                }
            }
            putUtf8(zos, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">"
                            + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                            + "<dc:title>Inline Fixture</dc:title><dc:creator>Tester</dc:creator>"
                            + "</metadata><manifest>" + manifest + "</manifest>"
                            + "<spine><itemref idref=\"ch1\"/></spine></package>");
            putUtf8(zos, "OEBPS/ch1.xhtml", chapterXhtml);
            if (extraEntries != null) {
                for (Map.Entry<String, byte[]> e : extraEntries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }
        }
        return file;
    }

    private static void putUtf8(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static Bitmap renderFirstPage(Application app, EpubBook book, ReaderTheme theme,
                                          int linePct, boolean forbidSaveLayer) {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.sizeSp = 20;
        prefs.linePct = linePct;
        PagedBook paged = EpubPaginator.paginate(book, TextStyles.create(app, prefs), 720, 900);
        PageView view = new PageView(app);
        view.setData(paged, paged.page(0), theme, null);
        return drawView(view, 720, 900, forbidSaveLayer);
    }

    private static Bitmap drawView(PageView view, int w, int h, boolean forbidSaveLayer) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = forbidSaveLayer ? new NoSaveLayerCanvas(bmp) : new Canvas(bmp);
        view.draw(canvas);
        return bmp;
    }

    private static byte[] makeMonochromeGlyphPng(int w, int h) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF111111);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        c.drawCircle(w / 2f, h / 2f, w * 0.36f, p);
        c.drawLine(w * 0.2f, h / 2f, w * 0.8f, h / 2f, p);
        c.drawLine(w / 2f, h * 0.2f, w / 2f, h * 0.8f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static byte[] makeColorBadgePng(int w, int h) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE11D48); // vivid crimson
        c.drawRoundRect(new RectF(2, 2, w / 2f, h - 2), 8f, 8f, p);
        p.setColor(0xFF2563EB); // vivid royal blue
        c.drawRoundRect(new RectF(w / 2f, 2, w - 2, h - 2), 8f, 8f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static int countNonBgPixels(Bitmap bmp, int bg) {
        int n = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                if (bmp.getPixel(x, y) != bg) n++;
            }
        }
        return n;
    }

    private static int countColorfulPixels(Bitmap bmp) {
        int n = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (spread > 60) n++;
            }
        }
        return n;
    }

    private static int countBrightPixels(Bitmap bmp, int minLum) {
        int n = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                int lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
                if (lum >= minLum) n++;
            }
        }
        return n;
    }

    private static final class NoSaveLayerCanvas extends Canvas {
        NoSaveLayerCanvas(Bitmap bitmap) {
            super(bitmap);
        }

        @Override
        public int saveLayer(RectF bounds, Paint paint) {
            throw new AssertionError("PageView must never allocate offscreen saveLayer");
        }

        @Override
        public int saveLayer(float l, float t, float r, float b, Paint paint) {
            throw new AssertionError("PageView must never allocate offscreen saveLayer");
        }
    }
}
