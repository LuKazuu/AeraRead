package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Bookmark;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.SeekableSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.reader.EpubSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exhaustive parser, pagination, pixel-clipping, horizontal overhang, surrogate-pair,
 * malformed-HTML, bookmark/resume, and multi-theme tests for EPUB {@code <ruby>} annotations.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class RubyTypographyTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        app.getSharedPreferences("aera_reader", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        TestSupport.clearLibrary(app);
    }

    private File buildRubyEpub(String fileName, String navTitleHtml, String bodyHtml)
            throws Exception {
        File out = new File(app.getCacheDir(), fileName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            putEntry(zos, "mimetype", "application/epub+zip");
            putEntry(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<container version=\"1.0\" "
                            + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                            + "media-type=\"application/oebps-package+xml\"/></rootfiles>"
                            + "</container>");
            putEntry(zos, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<package version=\"3.0\" xmlns=\"http://www.idpf.org/2007/opf\" "
                            + "unique-identifier=\"id\">"
                            + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                            + "<dc:identifier id=\"id\">ruby-test</dc:identifier>"
                            + "<dc:title>Ruby Test Book</dc:title>"
                            + "<dc:language>ja</dc:language>"
                            + "</metadata>"
                            + "<manifest>"
                            + "<item id=\"nav\" href=\"nav.xhtml\" "
                            + "media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                            + "<item id=\"c1\" href=\"ch1.xhtml\" "
                            + "media-type=\"application/xhtml+xml\"/>"
                            + "</manifest>"
                            + "<spine><itemref idref=\"c1\"/></spine>"
                            + "</package>");
            putEntry(zos, "OEBPS/nav.xhtml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<html xmlns=\"http://www.w3.org/1999/xhtml\" "
                            + "xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>"
                            + "<nav epub:type=\"toc\"><ol>"
                            + "<li><a href=\"ch1.xhtml\">" + navTitleHtml + "</a></li>"
                            + "</ol></nav></body></html>");
            putEntry(zos, "OEBPS/ch1.xhtml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                            + bodyHtml + "</body></html>");
        }
        return out;
    }

    private static void putEntry(ZipOutputStream zos, String path, String content)
            throws Exception {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private EpubBook parseEpub(File epubFile) throws Exception {
        try (SeekableSource source = new FileSource(epubFile);
             ZipArchive zip = ZipArchive.open(source)) {
            EpubBook parsed = new EpubParser(zip).parse("Ruby");
            parsed.zip = null;
            return parsed;
        }
    }

    @Test
    public void parsesStandardRpMultiPairW3cAndNestedInlineRubyCleanly() throws Exception {
        File epub = buildRubyEpub("variants.epub",
                "<ruby>第一章<rp>（</rp><rt>だいいっしょう</rt><rp>）</rp></ruby>",
                "<h1><ruby>序章<rp>(</rp><rt>じょしょう</rt><rp>)</rp></ruby></h1>"
                        + "<p>私は<ruby>東<rt>とう</rt>京<rt>きょう</rt></ruby>に行く。</p>"
                        + "<p><ruby><rbc><rb>漢</rb><rb>字</rb></rbc>"
                        + "<rtc><rt>かん</rt><rt>じ</rt></rtc></ruby>を学ぶ。</p>"
                        + "<p><ruby><rb>今</rb><rb>日</rb><rt>きょう</rt></ruby>の空。</p>"
                        + "<blockquote><ruby><span class=\"b\">無常</span>"
                        + "<rt><span>むじょう</span></rt></ruby>の響き。</blockquote>"
                        + "<ul><li><ruby>北京<rt>Běijīng</rt></ruby>欢迎你。</li></ul>");

        EpubBook book = parseEpub(epub);

        assertEquals(1, book.toc.size());
        assertEquals("第一章", book.toc.get(0).title);

        List<EpubBook.Block> blocks = book.chapters.get(0).blocks;
        assertEquals(6, blocks.size());

        assertEquals(EpubBook.Block.STYLE_H1, blocks.get(0).style);
        assertEquals("序章", blocks.get(0).text);
        assertEquals(1, blocks.get(0).rubies.size());
        assertEquals("じょしょう", blocks.get(0).rubies.get(0).text);
        assertEquals(0, blocks.get(0).rubies.get(0).start);
        assertEquals(2, blocks.get(0).rubies.get(0).end);

        assertEquals("私は東京に行く。", blocks.get(1).text);
        assertEquals(2, blocks.get(1).rubies.size());
        assertEquals("東", blocks.get(1).text.substring(
                blocks.get(1).rubies.get(0).start, blocks.get(1).rubies.get(0).end));
        assertEquals("とう", blocks.get(1).rubies.get(0).text);
        assertEquals("京", blocks.get(1).text.substring(
                blocks.get(1).rubies.get(1).start, blocks.get(1).rubies.get(1).end));
        assertEquals("きょう", blocks.get(1).rubies.get(1).text);

        assertEquals("漢字を学ぶ。", blocks.get(2).text);
        assertEquals(2, blocks.get(2).rubies.size());
        assertEquals("かん", blocks.get(2).rubies.get(0).text);
        assertEquals("じ", blocks.get(2).rubies.get(1).text);

        assertEquals("今日の空。", blocks.get(3).text);
        assertEquals(1, blocks.get(3).rubies.size());
        assertEquals("今日", blocks.get(3).text.substring(
                blocks.get(3).rubies.get(0).start, blocks.get(3).rubies.get(0).end));
        assertEquals("きょう", blocks.get(3).rubies.get(0).text);

        assertEquals(EpubBook.Block.STYLE_QUOTE, blocks.get(4).style);
        assertEquals("無常の響き。", blocks.get(4).text);
        assertEquals("むじょう", blocks.get(4).rubies.get(0).text);

        assertEquals(EpubBook.Block.STYLE_LIST, blocks.get(5).style);
        assertEquals("北京欢迎你。", blocks.get(5).text);
        assertEquals("Běijīng", blocks.get(5).rubies.get(0).text);
    }

    @Test
    public void prettyPrintedMultilineCjkSurrogatesBrSplitsAndMalformedTagsAreExact()
            throws Exception {
        String yoshi = "\uD842\uDFB7";
        File epub = buildRubyEpub("edge_cases.epub", "テスト",
                "<p>\n  吾輩は\n  <ruby>\n    猫\n    <rp>（</rp>\n    <rt>ねこ</rt>\n    "
                        + "<rp>）</rp>\n  </ruby>\n  である。\n</p>"
                        + "<p>   <ruby>" + yoshi + "野家<rt>よしのや</rt></ruby>に行く。<br/>"
                        + "次の<ruby>行<rt>ぎょう</rt></ruby>です。</p>"
                        + "<p><ruby>   </ruby><ruby>素<rt>   </rt></ruby>"
                        + "<ruby><rt>かな</rt></ruby>終わり</p>"
                        + "<p>ルビなしの段落です。</p>");

        EpubBook book = parseEpub(epub);
        List<EpubBook.Block> blocks = book.chapters.get(0).blocks;
        assertEquals(5, blocks.size());

        assertEquals("吾輩は猫である。", blocks.get(0).text);
        assertEquals(1, blocks.get(0).rubies.size());
        assertEquals("猫", blocks.get(0).text.substring(
                blocks.get(0).rubies.get(0).start, blocks.get(0).rubies.get(0).end));

        assertEquals(yoshi + "野家に行く。", blocks.get(1).text);
        assertEquals(1, blocks.get(1).rubies.size());
        assertEquals(0, blocks.get(1).rubies.get(0).start);
        assertEquals(4, blocks.get(1).rubies.get(0).end);
        assertEquals(yoshi + "野家", blocks.get(1).text.substring(0, 4));

        assertEquals("次の行です。", blocks.get(2).text);
        assertEquals(1, blocks.get(2).rubies.size());
        assertEquals("行", blocks.get(2).text.substring(
                blocks.get(2).rubies.get(0).start, blocks.get(2).rubies.get(0).end));
        assertEquals("ぎょう", blocks.get(2).rubies.get(0).text);

        assertEquals("素かな終わり", blocks.get(3).text);
        assertNull(blocks.get(3).rubies);

        assertNull(blocks.get(4).rubies);
    }

    @Test
    public void topOfPageRubyNeverClipsAtTightLeadingAndDrawsWithoutSaveLayer() throws Exception {
        TestSupport.installFont(app, "notosc");

        EpubBook withRuby = parseEpub(buildRubyEpub("top_ruby.epub", "一",
                "<p><ruby>漢字<rt>かんじ</rt></ruby></p>"));
        EpubBook withoutRuby = parseEpub(buildRubyEpub("top_plain.epub", "一",
                "<p>漢字</p>"));

        for (ReaderTheme theme : ReaderTheme.values()) {
            ReaderPrefs prefs = new ReaderPrefs(app);
            prefs.font = "notosc";
            prefs.sizeSp = 24;
            prefs.linePct = 125;
            TextStyles st = TextStyles.create(app, prefs);

            PagedBook pagedRuby = EpubPaginator.paginate(withRuby, st, 700, 1400);
            PagedBook pagedPlain = EpubPaginator.paginate(withoutRuby, st, 700, 1400);

            int rubyLineHeight = pagedRuby.page(0).segments.get(0).height;
            int plainLineHeight = pagedPlain.page(0).segments.get(0).height;
            assertTrue("ruby on tight line reserves extra vertical headroom ("
                    + rubyLineHeight + " > " + plainLineHeight + ")",
                    rubyLineHeight > plainLineHeight);

            Bitmap rubyBmp = renderSinglePageForbiddingSaveLayer(pagedRuby, theme);
            Bitmap plainBmp = renderSinglePageForbiddingSaveLayer(pagedPlain, theme);

            int padTop = 40;
            int topBoundaryClippedPixels = countNonBgInRows(rubyBmp, theme.bg, padTop, padTop + 1);
            assertEquals("furigana must never be sliced at top clip boundary (" + theme.key + ")",
                    0, topBoundaryClippedPixels);

            // Align both images by their baseline from the bottom of the line so we compare
            // the exact pixel region ABOVE the base kanji strokes!
            int shift = rubyLineHeight - plainLineHeight;
            int upperBandRubyInk = countNonBgInRows(rubyBmp, theme.bg, padTop + 1, padTop + shift + 8);
            int upperBandPlainInk = countNonBgInRows(plainBmp, theme.bg, padTop + 1, padTop + 8);
            assertTrue("furigana rendered visible glyphs above base kanji on theme " + theme.key
                            + " (ruby=" + upperBandRubyInk + ", plain=" + upperBandPlainInk + ")",
                    upperBandRubyInk > upperBandPlainInk + 30);

            rubyBmp.recycle();
            plainBmp.recycle();
        }
    }

    private Bitmap renderSinglePageForbiddingSaveLayer(PagedBook paged, ReaderTheme theme) {
        PageView view = new PageView(app, false);
        view.setPadding(40, 40, 40, 40);
        view.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1480, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 780, 1480);
        view.setData(paged, paged.page(0), theme, null);

        Bitmap bmp = Bitmap.createBitmap(780, 1480, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(theme.bg);
        Canvas noLayerCanvas = new Canvas(bmp) {
            @Override
            public int saveLayer(RectF bounds, Paint paint) {
                throw new AssertionError("saveLayer must never be used when drawing ruby");
            }

            @Override
            public int saveLayer(float l, float t, float r, float b, Paint paint) {
                throw new AssertionError("saveLayer must never be used when drawing ruby");
            }
        };
        view.draw(noLayerCanvas);
        return bmp;
    }

    private static int countNonBgInRows(Bitmap bmp, int bg, int yStart, int yEnd) {
        int count = 0;
        for (int y = yStart; y < yEnd; y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                if (bmp.getPixel(x, y) != bg) count++;
            }
        }
        return count;
    }

    @Test
    public void wideReadingExpandsAdvanceAndExtremeGikunClampsInsidePageWidth() throws Exception {
        TestSupport.installFont(app, "notosc");
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.font = "notosc";
        prefs.sizeSp = 30;
        TextStyles st = TextStyles.create(app, prefs);

        EpubBook shortKanjiWideRuby = parseEpub(buildRubyEpub("wide.epub", "一",
                "<p><ruby>承<rt>うけたまわ</rt></ruby></p>"));
        EpubBook plainSingleKanji = parseEpub(buildRubyEpub("plain1.epub", "一", "<p>承</p>"));
        PagedBook pWide = EpubPaginator.paginate(shortKanjiWideRuby, st, 600, 1200);
        PagedBook pPlain = EpubPaginator.paginate(plainSingleKanji, st, 600, 1200);
        float wideLineW = pWide.page(0).segments.get(0).layout.getLineWidth(0);
        float plainLineW = pPlain.page(0).segments.get(0).layout.getLineWidth(0);
        assertTrue("5-kana reading widens single-kanji advance (" + wideLineW + " > "
                + plainLineW + ")", wideLineW > plainLineW * 1.4f);

        EpubBook extremeGikun = parseEpub(buildRubyEpub("gikun.epub", "一",
                "<p><ruby>聖剣<rt>エクスカリバー・プロミネンス・ストライク・オブ・ジ・エンド</rt></ruby></p>"));
        PagedBook pGikun = EpubPaginator.paginate(extremeGikun, st, 260, 1200);
        float gikunLineW = pGikun.page(0).segments.get(0).layout.getLineWidth(0);
        assertTrue("extreme reading never overflows column width 260: " + gikunLineW,
                gikunLineW <= 260.5f);
        Bitmap rendered = renderSinglePageForbiddingSaveLayer(pGikun, ReaderTheme.LIGHT);
        rendered.recycle();
    }

    @Test
    public void bookmarksResumeAndLiveReflowStayExactAcrossRubyPages() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("<p>第").append(i + 1)
                    .append("段落では<ruby>吾輩<rt>わがはい</rt></ruby>は<ruby>猫<rt>ねこ</rt></ruby>である。")
                    .append("<ruby>名前<rt>なまえ</rt></ruby>はまだ<ruby>無<rt>な</rt></ruby>い。</p>");
        }
        File file = buildRubyEpub("resume_ruby.epub",
                "<ruby>本編<rt>ほんぺん</rt></ruby>", body.toString());
        Book book = FolderScanner.describe(app, Uri.fromFile(file).toString(),
                "resume_ruby.epub", file.length(), file.lastModified());
        List<Book> books = Library.books(app);
        books.add(book);
        Library.saveBooks(app, books);

        int targetPage = 3;
        int expectedOffset;
        try (ActivityScenario<ReaderActivity> s1 = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class).putExtra(
                        ReaderActivity.EXTRA_BOOK_ID, book.id()))) {
            ReaderActivity a1 = TestSupport.currentActivity(s1);
            TestSupport.waitUntil(() -> a1.getSession() != null, 8000, "loaded");
            a1.runOnUiThread(() -> {
                a1.goToPage(targetPage);
                a1.getSession().toggleBookmark(targetPage);
            });
            TestSupport.idle();
            expectedOffset = ((EpubSession) a1.getSession()).offsetAtTopOf(targetPage);
            assertTrue(a1.getSession().isBookmarked(targetPage));
        }

        try (ActivityScenario<ReaderActivity> s2 = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class).putExtra(
                        ReaderActivity.EXTRA_BOOK_ID, book.id()))) {
            ReaderActivity a2 = TestSupport.currentActivity(s2);
            TestSupport.waitUntil(() -> a2.getSession() != null, 8000, "reloaded");
            assertEquals(targetPage, a2.getCurrentPage());
            assertEquals(expectedOffset,
                    ((EpubSession) a2.getSession()).offsetAtTopOf(a2.getCurrentPage()));
            List<Bookmark> marks = a2.getSession().bookmarks();
            assertEquals(1, marks.size());
        }
    }

    @Test
    public void scrollModeLiveReflowAndVectorZoomWorkOnRubyPages() throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("<h1><ruby>吾輩<rt>わがはい</rt></ruby>は<ruby>猫<rt>ねこ</rt></ruby>である</h1>");
        for (int i = 0; i < 24; i++) {
            body.append("<p>「<ruby>今日<rt>きょう</rt></ruby>の<ruby>天気<rt>てんき</rt></ruby>は")
                    .append("<ruby>晴朗<rt>せいろう</rt></ruby>なれども<ruby>波高<rt>なみたか</rt></ruby>し、")
                    .append("ご<ruby>了承<rt>りょうしょう</rt></ruby>ください。」")
                    .append("<ruby>約束の土地<rt>エクスカリバー・プロミネンス・ストライク</rt></ruby>。</p>");
        }
        File file = buildRubyEpub("scroll_zoom_ruby.epub", "一", body.toString());
        Book book = FolderScanner.describe(app, Uri.fromFile(file).toString(),
                "scroll_zoom_ruby.epub", file.length(), file.lastModified());
        List<Book> books = Library.books(app);
        books.add(book);
        Library.saveBooks(app, books);

        ReaderPrefs p = new ReaderPrefs(app);
        p.viewMode = "scroll";
        p.sizeSp = 18;
        p.linePct = 145;
        p.save();

        // Sweep min -> max -> min font sizes & line spacing across ruby pages without crash
        int[] sizes = {14, 30, 14, 24, 18};
        int[] spacings = {125, 190, 125, 150};
        for (int k = 0; k < sizes.length; k++) {
            ReaderPrefs cur = new ReaderPrefs(app);
            cur.viewMode = (k % 2 == 0) ? "scroll" : "pages";
            cur.sizeSp = sizes[k];
            cur.linePct = spacings[k % spacings.length];
            cur.save();
            try (ActivityScenario<ReaderActivity> s = ActivityScenario.launch(
                    new Intent(app, ReaderActivity.class).putExtra(
                            ReaderActivity.EXTRA_BOOK_ID, book.id()))) {
                ReaderActivity a = TestSupport.currentActivity(s);
                TestSupport.waitUntil(() -> a.getSession() != null, 8000, "loaded");
                TestSupport.layout(a);
                Bitmap zoomed = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888);
                try {
                    Canvas guard = new Canvas(zoomed) {
                        @Override
                        public int saveLayer(float l, float t, float r, float b,
                                             android.graphics.Paint paint) {
                            throw new AssertionError("Zoomed ruby view called saveLayer!");
                        }
                    };
                    guard.save();
                    guard.scale(2.5f, 2.5f, 270f, 300f);
                    a.getWindow().getDecorView().draw(guard);
                    guard.restore();
                    assertTrue(countNonBgInRows(zoomed, ReaderTheme.LIGHT.bg, 0, 959) > 500);
                } finally {
                    zoomed.recycle();
                }
            }
        }
    }
}
