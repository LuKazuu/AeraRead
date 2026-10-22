package com.aera.read.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Font size and line spacing have to reach the glyphs, not just the preference file: the
 * paint size, the leading and the resulting pagination must all move with the sliders.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class TypographyTest {

    private EpubBook parse(String name) throws Exception {
        try (ZipArchive zip = ZipArchive.open(new FileSource(TestSupport.resourceFile(name)))) {
            EpubBook book = new EpubParser(zip).parse(name);
            book.zip = null;
            return book;
        }
    }

    private TextStyles styles(Application app, int sizeSp, int linePct) {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.sizeSp = sizeSp;
        prefs.linePct = linePct;
        prefs.font = "system";
        return TextStyles.create(app, prefs);
    }

    @Test
    public void sizeChangesTheGlyphsAndEachLineGetsTaller() {
        Application app = ApplicationProvider.getApplicationContext();
        TextStyles small = styles(app, 15, 155);
        TextStyles large = styles(app, 27, 155);

        assertTrue("body paint grows with the size slider",
                large.body.getTextSize() > small.body.getTextSize() * 1.5f);
        assertTrue("headings scale with the body",
                large.h1.getTextSize() > small.h1.getTextSize() * 1.5f);
        assertTrue("line box grows with the font",
                large.lineExtra + large.sizePx > small.lineExtra + small.sizePx);
    }

    @Test
    public void lineSpacingChangesTheLeadingWithoutTouchingTheGlyphSize() {
        Application app = ApplicationProvider.getApplicationContext();
        TextStyles tight = styles(app, 19, 125);
        TextStyles loose = styles(app, 19, 190);

        assertTrue("same glyph size", Math.abs(tight.body.getTextSize()
                - loose.body.getTextSize()) < 0.01f);
        assertTrue("loose spacing pushes lines apart", loose.lineExtra > tight.lineExtra + 1f);
    }

    /** Every slider step has to move the text, for the system face and downloaded ones. */
    @Test
    public void everyLineSpacingStepChangesTheLineBox() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.installFont(app, "lora");
        TestSupport.installFont(app, "inter");
        TestSupport.installFont(app, "notosc");
        for (String font : new String[]{"system", "lora", "inter", "notosc"}) {
            float previous = -1f;
            for (int pct = 125; pct <= 190; pct += 5) {
                ReaderPrefs prefs = new ReaderPrefs(app);
                prefs.font = font;
                prefs.sizeSp = 19;
                prefs.linePct = pct;
                TextStyles styles = TextStyles.create(app, prefs);
                float box = styles.sizePx * pct / 100f;
                float actual = styles.body.getFontMetrics(null) + styles.lineExtra;
                assertEquals(font + " at " + pct + "%", box, actual, 0.01f);
                assertTrue(font + " at " + pct + "% must be looser than the step below",
                        actual > previous);
                previous = actual;
            }
            assertTrue(font + " spans a real range", previous > 48f * 1.4f);
        }
    }

    @Test
    public void bothControlsChangeHowMuchTextFitsOnAPage() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("meditations.epub");

        PagedBook base = EpubPaginator.paginate(book, styles(app, 19, 155), 900, 1500);
        PagedBook bigger = EpubPaginator.paginate(book, styles(app, 24, 155), 900, 1500);
        PagedBook looser = EpubPaginator.paginate(book, styles(app, 19, 185), 900, 1500);

        assertTrue("bigger type needs more pages", bigger.totalPages > base.totalPages);
        assertTrue("wider leading needs more pages", looser.totalPages > base.totalPages);

        int baseChars = base.page(1).endOffset - base.page(1).startOffset;
        int biggerChars = bigger.page(1).endOffset - bigger.page(1).startOffset;
        assertTrue("fewer characters fit at a larger size", biggerChars < baseChars);

        int looserChars = looser.page(1).endOffset - looser.page(1).startOffset;
        assertTrue("fewer characters fit with wider leading", looserChars < baseChars);
    }

    @Test
    public void theSamePageIsDrawnAtTheSizeTheSheetShows() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("alice.epub");

        PagedBook paged = EpubPaginator.paginate(book, styles(app, 22, 155), 900, 1500);
        float expected = com.aera.read.util.Ui.sp(app, 22);
        assertNotEquals(0f, paged.styles.body.getTextSize());
        assertTrue("layout uses the size the reader asked for",
                Math.abs(paged.styles.body.getTextSize() - expected) < 0.5f);
    }
}
