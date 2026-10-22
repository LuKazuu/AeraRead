package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Typeface;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.util.FontCatalog;
import com.aera.read.util.FontStore;
import com.aera.read.util.Fonts;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/** The download list has to stay consistent, and a download has to become a real face. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class FontCatalogTest {

    @Test
    public void everyEntryIsDistinctAndPointingAtTheUpstreamRepository() {
        Set<String> ids = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            assertTrue("duplicate id " + entry.id, ids.add(entry.id));
            assertTrue("duplicate label " + entry.label, labels.add(entry.label));
            assertTrue(entry.id + " must be a plain key", entry.id.matches("[a-z0-9]+"));
            assertTrue(entry.url, entry.url.startsWith(
                    "https://raw.githubusercontent.com/google/fonts/main/ofl/"));
            assertTrue(entry.url, entry.url.endsWith(".ttf"));
            assertTrue(entry.bytes > 1000);
        }
        assertTrue("enough choice", FontCatalog.ALL.length >= 20);
    }

    @Test
    public void bothGroupsAreOfferedAndCjkCoversTheLanguages() {
        int latin = 0;
        int cjk = 0;
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            if (entry.isCjk()) cjk++;
            else latin++;
        }
        assertTrue(latin >= 10);
        assertTrue(cjk >= 5);
        assertTrue(FontCatalog.byId("notosc").isCjk());
        assertTrue(FontCatalog.byId("notokr").isCjk());
        assertNull(FontCatalog.byId("nope"));
    }

    @Test
    public void systemIsAlwaysAvailableAndEverythingElseIsNot() {
        Application app = ApplicationProvider.getApplicationContext();
        assertTrue(Fonts.isAvailable(app, Fonts.SYSTEM));
        assertTrue(Fonts.isAvailable(app, null));
        assertNotNull(Fonts.get(app, Fonts.SYSTEM, false, false));
        assertNotNull(Fonts.get(app, Fonts.SYSTEM, true, true));
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            assertEquals(entry.id, false, FontStore.isInstalled(app, entry.id));
            assertEquals(entry.id, false, Fonts.isAvailable(app, entry.id));
        }
        // an unknown key is not a font, and asking for one must not throw
        assertNotNull(Fonts.get(app, "does-not-exist", true, false));
        assertEquals("System", FontCatalog.label(Fonts.SYSTEM));
    }

    @Test
    public void aDownloadedFontRendersRealGlyphs() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.installFont(app, "lora");
        assertTrue(FontStore.isInstalled(app, "lora"));

        Typeface lora = Fonts.get(app, "lora", false, false);
        assertTrue("Lora covers Latin", covers(lora, "Reading in Lora"));
        assertNotNull(Fonts.get(app, "lora", true, false));
        assertNotNull(Fonts.get(app, "lora", false, true));
    }

    @Test
    public void aCjkDownloadCoversChineseJapaneseAndKorean() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.installFont(app, "notosc");
        Typeface cjk = Fonts.get(app, "notosc", false, false);
        assertTrue("covers hanzi", covers(cjk, "永漢中文"));
        assertTrue("covers kana", covers(cjk, "あカ"));
        assertTrue("covers hangul", covers(cjk, "한국어"));
    }

    @Test
    public void removingTheFileSendsLookupsBackToTheSystemFace() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.installFont(app, "inter");
        Typeface inter = Fonts.get(app, "inter", false, false);
        assertNotNull(inter);

        assertTrue(FontStore.remove(app, "inter"));
        Fonts.invalidate();
        assertEquals(false, FontStore.isInstalled(app, "inter"));
        assertEquals(false, Fonts.isAvailable(app, "inter"));
        assertNotNull("still renders after removal", Fonts.get(app, "inter", false, false));
        assertEquals(false, FontStore.remove(app, "inter"));
    }

    @Test
    public void sizesReadLikeAPersonWroteThem() {
        assertEquals("17.8 MB", FontCatalog.sizeLabel(17772300));
        assertEquals("212 KB", FontCatalog.sizeLabel(212196));
        assertEquals("1 KB", FontCatalog.sizeLabel(1));
    }

    /** A face covers the text when every character has a real (non-missing) glyph. */
    private static boolean covers(Typeface face, String text) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTypeface(face);
        paint.setTextSize(32f);
        float[] widths = new float[text.length()];
        paint.getTextWidths(text, widths);
        for (float width : widths) {
            if (width <= 0f) return false;
        }
        return true;
    }
}
