package com.aera.read.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.ui.FontLibrarySheet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/**
 * The download sheet: it installs and deletes files, and it never decides which font the
 * reader uses. Choosing happens on the appearance chips.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class FontLibrarySheetTest {

    private Application app;
    private android.content.Context themed;
    private ReaderPrefs prefs;
    private FontLibrarySheet sheet;
    private int changes;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        themed = new android.view.ContextThemeWrapper(app, R.style.Theme_Aera);
        prefs = new ReaderPrefs(app);
        prefs.font = Fonts.SYSTEM;
        prefs.save();
        FontStore.setDownloader(null);
        changes = 0;
    }

    private void open() {
        sheet = new FontLibrarySheet(themed, () -> changes++);
        sheet.show();
    }

    private ViewGroup list() {
        return ShadowDialog.getLatestDialog().findViewById(R.id.font_list);
    }

    private LinearLayout rowFor(String label) {
        ViewGroup list = list();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!(child instanceof LinearLayout) || child.getId() != R.id.font_row) continue;
            TextView name = child.findViewById(R.id.font_name);
            if (name != null && label.contentEquals(name.getText())) {
                return (LinearLayout) child;
            }
        }
        return null;
    }

    private String stateOf(String label) {
        return ((TextView) rowFor(label).findViewById(R.id.font_state)).getText().toString();
    }

    private View actionOf(String label) {
        return rowFor(label).findViewById(R.id.font_action);
    }

    @Test
    public void theListIsOnlyDownloadsSoThereIsNoSystemRow() {
        open();
        assertNull("the system face is not a download", rowFor("System"));
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            assertNotNull(entry.label + " is missing", rowFor(entry.label));
        }
    }

    @Test
    public void anUninstalledFontShowsItsSizeAndHasNoRemoveButton() {
        open();
        String state = stateOf("Lora");
        assertTrue(state, state.startsWith("Download"));
        assertTrue(state, state.contains("212 KB"));
        assertEquals(View.GONE, actionOf("Lora").getVisibility());
    }

    @Test
    public void tappingADownloadsItButDoesNotChooseIt() throws Exception {
        final byte[] bytes = TestSupport.readFont("lora");
        FontStore.setDownloader(url -> new FontStore.Body(new ByteArrayInputStream(bytes),
                bytes.length));
        open();

        rowFor("Lora").performClick();
        TestSupport.waitUntil(() -> FontStore.isInstalled(app, "lora"), 15000, "installed");
        TestSupport.idle();

        assertEquals("Installed", stateOf("Lora"));
        assertEquals(View.VISIBLE, actionOf("Lora").getVisibility());
        assertEquals("downloading must not change the reading font", Fonts.SYSTEM, prefs.font);
        assertTrue("the appearance sheet is told", changes > 0);
        assertNotNull("the sheet is still open", ShadowDialog.getLatestDialog());
    }

    @Test
    public void tappingAnInstalledFontDoesNothingAtAll() throws Exception {
        TestSupport.installFont(app, "inter");
        open();
        rowFor("Inter").performClick();
        TestSupport.idle();
        assertEquals(Fonts.SYSTEM, prefs.font);
        assertEquals("Installed", stateOf("Inter"));
    }

    @Test
    public void removingDeletesTheFileAndKeepsTheSheetUsable() throws Exception {
        TestSupport.installFont(app, "inter");
        prefs.font = "inter";
        prefs.save();
        open();

        actionOf("Inter").performClick();
        TestSupport.idle();

        assertFalse(FontStore.isInstalled(app, "inter"));
        assertTrue(stateOf("Inter").startsWith("Download"));
        assertEquals(View.GONE, actionOf("Inter").getVisibility());
        assertEquals("removal is reported so the reader can fall back", 1, changes);
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void aFailedDownloadSaysSoAndStoresNothing() {
        FontStore.setDownloader(url -> {
            throw new IOException("offline");
        });
        open();
        rowFor("Bitter").performClick();
        TestSupport.waitUntil(() -> stateOf("Bitter").contains("failed"), 15000, "failure shown");
        assertFalse(FontStore.isInstalled(app, "bitter"));
        File[] files = FontStore.dir(app).listFiles();
        assertTrue(files == null || files.length == 0);
        assertTrue("the row stays tappable for a retry", rowFor("Bitter").isEnabled());
    }

    @Test
    public void aRetryAfterAFailureInstallsTheFont() throws Exception {
        final byte[] bytes = TestSupport.readFont("lora");
        final boolean[] online = {false};
        FontStore.setDownloader(url -> {
            if (!online[0]) throw new IOException("offline");
            return new FontStore.Body(new ByteArrayInputStream(bytes), bytes.length);
        });
        open();
        rowFor("Bitter").performClick();
        TestSupport.waitUntil(() -> stateOf("Bitter").contains("failed"), 15000, "failed once");

        online[0] = true;
        rowFor("Bitter").performClick();
        TestSupport.waitUntil(() -> FontStore.isInstalled(app, "bitter"), 15000, "retry works");
        TestSupport.idle();
        assertEquals("Installed", stateOf("Bitter"));
    }
}
