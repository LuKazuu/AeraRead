package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.slider.Slider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Thorough coverage for CJK parsing, font sizing, line spacing, live sliders, and repo hygiene. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class CjkTypographyTest {

    private static final String CJK_PROSE =
            "漢字の本を読む。日本語の文章です。中文书籍。这是一段中文文本。한국어 책입니다. 한글 문장입니다。";

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        app.getSharedPreferences("aera_reader", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void defaultFontSizeIsEighteen() throws Exception {
        ReaderPrefs fresh = new ReaderPrefs(app);
        assertEquals(18, fresh.sizeSp);

        Book book = TestSupport.install(app, "meditations.epub");
        try (ActivityScenario<ReaderActivity> scenario = launch(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            activity.runOnUiThread(() -> {
                MaterialToolbar bar = activity.findViewById(R.id.top_bar);
                bar.getMenu().performIdentifierAction(R.id.action_appearance, 0);
            });
            TestSupport.idle();
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            Slider sizeSlider = dialog.findViewById(R.id.size_slider);
            TextView sizeValue = dialog.findViewById(R.id.size_value);
            assertEquals(18f, sizeSlider.getValue(), 0.01f);
            assertTrue(sizeValue.getText().toString().contains("18"));
        }
    }

    @Test
    public void everyFontSizeStepChangesCjkGlyphsAndPagination() throws Exception {
        TestSupport.installFont(app, "notosc");
        EpubBook book = cjkBook(CJK_PROSE.repeat(24), false);

        for (String font : new String[]{"system", "notosc"}) {
            float prevGlyph = -1f;
            int prevBox = -1;
            int prevPages = -1;
            for (int size = 14; size <= 30; size++) {
                ReaderPrefs p = new ReaderPrefs(app);
                p.font = font;
                p.sizeSp = size;
                p.linePct = 155;
                TextStyles st = TextStyles.create(app, p);
                float glyph = st.body.measureText("漢");
                int box = st.lineBoxPx(st.body);
                PagedBook paged = EpubPaginator.paginate(book, st, 700, 1400);
                assertTrue(font + " glyph grows at " + size, glyph > prevGlyph);
                assertTrue(font + " line box grows at " + size, box > prevBox);
                assertTrue(font + " pages do not shrink at " + size,
                        paged.totalPages >= prevPages);
                prevGlyph = glyph;
                prevBox = box;
                prevPages = paged.totalPages;
            }
            ReaderPrefs small = new ReaderPrefs(app);
            small.font = font;
            small.sizeSp = 14;
            ReaderPrefs large = new ReaderPrefs(app);
            large.font = font;
            large.sizeSp = 28;
            int smallPages = EpubPaginator.paginate(
                    book, TextStyles.create(app, small), 700, 1400).totalPages;
            int largePages = EpubPaginator.paginate(
                    book, TextStyles.create(app, large), 700, 1400).totalPages;
            assertTrue(font + " large size spans substantially more pages",
                    largePages > smallPages * 2);
        }
    }

    @Test
    public void everyLineSpacingStepChangesMultiLineCjkLines() throws Exception {
        TestSupport.installFont(app, "notosc");
        EpubBook book = cjkBook(CJK_PROSE.repeat(12), false);

        for (String font : new String[]{"system", "notosc"}) {
            int prevLayoutHeight = -1;
            for (int pct = 125; pct <= 190; pct += 5) {
                ReaderPrefs p = new ReaderPrefs(app);
                p.font = font;
                p.sizeSp = 18;
                p.linePct = pct;
                TextStyles st = TextStyles.create(app, p);
                PagedBook paged = EpubPaginator.paginate(book, st, 700, 4000);
                PagedBook.Segment seg = paged.page(0).segments.get(0);
                int expected = st.lineBoxPx(st.body);
                int lines = seg.layout.getLineCount();
                assertTrue(lines >= 4);
                for (int i = 0; i < lines; i++) {
                    int h = seg.layout.getLineBottom(i) - seg.layout.getLineTop(i);
                    assertEquals(font + " line " + i + " at " + pct + "%", expected, h);
                }
                assertTrue(font + " paragraph height grows at " + pct + "%",
                        seg.layout.getHeight() > prevLayoutHeight);
                prevLayoutHeight = seg.layout.getHeight();
            }
        }
    }

    @Test
    public void singleLineCjkDialogueParagraphsRespectLineSpacing() throws Exception {
        TestSupport.installFont(app, "notosc");
        EpubBook dialogueBook = cjkBook("「日本語の文章です。」", true);

        ReaderPrefs tight = new ReaderPrefs(app);
        tight.font = "notosc";
        tight.sizeSp = 18;
        tight.linePct = 125;

        ReaderPrefs mid = new ReaderPrefs(app);
        mid.font = "notosc";
        mid.sizeSp = 18;
        mid.linePct = 155;

        ReaderPrefs loose = new ReaderPrefs(app);
        loose.font = "notosc";
        loose.sizeSp = 18;
        loose.linePct = 190;

        PagedBook pTight = EpubPaginator.paginate(
                dialogueBook, TextStyles.create(app, tight), 700, 1200);
        PagedBook pMid = EpubPaginator.paginate(
                dialogueBook, TextStyles.create(app, mid), 700, 1200);
        PagedBook pLoose = EpubPaginator.paginate(
                dialogueBook, TextStyles.create(app, loose), 700, 1200);

        int strideTight = pTight.page(0).segments.get(1).y - pTight.page(0).segments.get(0).y;
        int strideMid = pMid.page(0).segments.get(1).y - pMid.page(0).segments.get(0).y;
        int strideLoose = pLoose.page(0).segments.get(1).y - pLoose.page(0).segments.get(0).y;

        assertTrue("1-line CJK stride grows from 125% to 155%", strideMid > strideTight);
        assertTrue("1-line CJK stride grows from 155% to 190%", strideLoose > strideMid);
        assertTrue("1-line CJK dialogue book repaginates into more pages when loosened",
                pLoose.totalPages > pTight.totalPages);
    }

    @Test
    public void parserJoinsWrappedCjkLinesAndSplitsIndentedAndRubyMarkup() throws Exception {
        String xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                + "<div class=\"calibre1\"><span class=\"calibre2\">漢字の本を読む。</span></div>"
                + "<div class=\"main_text\">日本語<ruby><rb>文</rb><rp>（</rp><rt>ぶん</rt>"
                + "<rp>）</rp></ruby>章です。<br/>中文书籍。</div>"
                + "<p>　　这是一段中文\n文本。\n　　한국어 책입니다.</p>"
                + "<p>Latin words\nstay separated.</p>"
                + "</body></html>";

        EpubBook parsed = parseXhtml(xhtml);
        List<EpubBook.Block> blocks = parsed.chapters.get(0).blocks;
        assertEquals(6, blocks.size());
        assertEquals("漢字の本を読む。", blocks.get(0).text);
        assertEquals("日本語文章です。", blocks.get(1).text);
        assertEquals("中文书籍。", blocks.get(2).text);
        assertEquals("　　这是一段中文文本。", blocks.get(3).text);
        assertEquals("　　한국어 책입니다.", blocks.get(4).text);
        assertEquals("Latin words stay separated.", blocks.get(5).text);
    }

    @Test
    public void appearanceSlidersCommitCjkChangesInPagesAndScrollMode() throws Exception {
        TestSupport.installFont(app, "notosc");
        for (String mode : new String[]{"pages", "scroll"}) {
            TestSupport.clearLibrary(app);
            ReaderPrefs p = new ReaderPrefs(app);
            p.font = "notosc";
            p.sizeSp = 16;
            p.linePct = 130;
            p.viewMode = mode;
            p.theme = "light";
            p.save();

            Book book = TestSupport.installFile(app,
                    TestSupport.writeEpubWithCjkText(app), "CJK-" + mode + ".epub");
            try (ActivityScenario<ReaderActivity> scenario = launch(book)) {
                ReaderActivity activity = TestSupport.currentActivity(scenario);
                int initialPages = activity.getSession().pageCount();

                AppearanceSheet sheet = new AppearanceSheet(activity, new ReaderPrefs(app),
                        true, true, new AppearanceSheet.Callback() {
                            @Override
                            public void onAppearanceChanged(boolean needsRelayout) {
                                activity.onAppearanceChanged(needsRelayout);
                            }

                            @Override
                            public void onAppearancePreview() {
                                activity.onAppearancePreview();
                            }
                        });

                // Simulate real slider drag followed immediately by finger lift AND a theme tap.
                activity.runOnUiThread(() -> {
                    sheet.previewSize(26);
                    sheet.applySize(26);
                    sheet.applyTheme("sepia");
                });
                TestSupport.waitUntil(() -> activity.getSession().pageCount() > initialPages,
                        15000, mode + ": size slider never re-paginated CJK book");
                int afterSizePages = activity.getSession().pageCount();

                activity.runOnUiThread(() -> {
                    sheet.previewLineSpacing(190);
                    sheet.applyLineSpacing(190);
                });
                TestSupport.waitUntil(() -> activity.getSession().pageCount() > afterSizePages,
                        15000, mode + ": line spacing slider never re-paginated CJK book");

                // Anchor must have been cleared on release so navigation holds the new page.
                activity.runOnUiThread(() -> activity.goToPage(2));
                TestSupport.idle();
                assertEquals(2, activity.getCurrentPage());
                activity.runOnUiThread(() -> {
                    sheet.previewLineSpacing(185);
                    sheet.applyLineSpacing(185);
                });
                TestSupport.idle();
                TestSupport.waitUntil(() -> new ReaderPrefs(app).linePct == 185, 5000, "saved");
                assertTrue(mode + ": turning pages must not snap back to page 0",
                        activity.getCurrentPage() > 0);
            }
        }
    }

    @Test
    public void onlyAeraPackageExistsAcrossSourceTree() throws Exception {
        Path root = new File(System.getProperty("user.dir")).toPath();
        // Gradle runs unit tests with cwd in app/ or root; handle both cleanly.
        Path appSrc = Files.isDirectory(root.resolve("src/main/java"))
                ? root.resolve("src") : root.resolve("app/src");
        assertTrue("source directory found", Files.isDirectory(appSrc));

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(appSrc)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        assertFalse(javaFiles.isEmpty());
        for (Path file : javaFiles) {
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue("unexpected package in " + file,
                    src.contains("package com.aera.read"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ActivityScenario<ReaderActivity> launch(Book book) {
        Intent intent = new Intent(app, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent);
        scenario.onActivity(TestSupport::layout);
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 15000, "reader ready");
        scenario.onActivity(TestSupport::layout);
        TestSupport.idle();
        return scenario;
    }

    private static EpubBook cjkBook(String paragraph, boolean manyShortLines) {
        EpubBook book = new EpubBook();
        book.title = "CJK";
        EpubBook.Chapter ch = new EpubBook.Chapter();
        ch.index = 0;
        int cursor = 0;
        int count = manyShortLines ? 40 : 4;
        for (int i = 0; i < count; i++) {
            EpubBook.Block block = EpubBook.Block.text(paragraph, EpubBook.Block.STYLE_BODY);
            block.charStart = cursor;
            cursor += paragraph.length();
            ch.blocks.add(block);
        }
        ch.charCount = cursor;
        book.chapters.add(ch);
        return book;
    }

    private static EpubBook parseXhtml(String xhtml) throws Exception {
        File epub = File.createTempFile("parse", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(epub))) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write(("<?xml version=\"1.0\"?><container version=\"1.0\" "
                    + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                    + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zip.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" "
                    + "unique-identifier=\"id\"><metadata "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:title>Test</dc:title></metadata><manifest>"
                    + "<item id=\"c1\" href=\"ch1.xhtml\" "
                    + "media-type=\"application/xhtml+xml\"/></manifest>"
                    + "<spine><itemref idref=\"c1\"/></spine></package>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(xhtml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try (ZipArchive archive = ZipArchive.open(new FileSource(epub))) {
            return new EpubParser(archive).parse("Test");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }
}
