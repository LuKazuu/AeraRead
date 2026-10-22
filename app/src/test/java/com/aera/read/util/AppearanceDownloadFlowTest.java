package com.aera.read.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.ui.AppearanceSheet;
import com.google.android.material.chip.Chip;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayInputStream;

/**
 * The whole font flow at the user's end: the manager downloads and deletes, the chip row
 * on the appearance sheet is where a face is actually chosen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class AppearanceDownloadFlowTest {

    private Application app;
    private android.content.Context themed;
    private ReaderPrefs prefs;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        themed = new ContextThemeWrapper(app, R.style.Theme_Aera);
        prefs = new ReaderPrefs(app);
        prefs.font = Fonts.SYSTEM;
        prefs.viewMode = "pages";
        prefs.save();
        FontStore.setDownloader(null);
    }

    private AppearanceSheet openSheet() {
        AppearanceSheet sheet = new AppearanceSheet(themed, prefs, true, true,
                new AppearanceSheet.Callback() {
                    @Override
                    public void onAppearanceChanged(boolean needsRelayout) {
                    }

                    @Override
                    public void onAppearancePreview() {
                    }
                });
        sheet.show();
        return sheet;
    }

    private LinearLayout rowNamed(android.app.Dialog dialog, String label) {
        LinearLayout list = dialog.findViewById(R.id.font_list);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getId() != R.id.font_row) continue;
            TextView name = child.findViewById(R.id.font_name);
            if (label.contentEquals(name.getText())) return (LinearLayout) child;
        }
        return null;
    }

    @Test
    public void downloadingAddsAChipAndChoosingItOnlyHappensThere() throws Exception {
        final byte[] bytes = TestSupport.readFont("lora");
        FontStore.setDownloader(url -> new FontStore.Body(new ByteArrayInputStream(bytes),
                bytes.length));

        openSheet();
        ViewGroup chips = ShadowDialog.getLatestDialog().findViewById(R.id.font_chips);
        assertEquals("only the system face to start with", 1, chips.getChildCount());

        android.app.Dialog appearance = ShadowDialog.getLatestDialog();
        View link = appearance.findViewById(R.id.fonts_button);
        assertNotNull("the sheet offers the font manager", link);
        link.performClick();
        TestSupport.idle();

        android.app.Dialog manager = ShadowDialog.getLatestDialog();
        assertTrue("a second sheet opened", manager != appearance);
        LinearLayout loraRow = rowNamed(manager, "Lora");
        assertNotNull(loraRow);
        loraRow.performClick();
        TestSupport.waitUntil(() -> FontStore.isInstalled(app, "lora"), 15000, "downloaded");

        assertEquals("the manager never changes the reading font", Fonts.SYSTEM, prefs.font);
        assertEquals("the chip appears right away", 2, chips.getChildCount());
        assertEquals("Lora", ((Chip) chips.getChildAt(1)).getText().toString());

        // choosing happens on the chip
        Chip lora = (Chip) chips.getChildAt(1);
        lora.performClick();
        assertEquals("lora", prefs.font);
        assertEquals("lora", new ReaderPrefs(app).font);
    }

    @Test
    public void removingTheFaceInUseFallsBackWithoutLeavingTheSheet() throws Exception {
        TestSupport.installFont(app, "lora");
        prefs.font = "lora";
        prefs.save();

        openSheet();
        android.app.Dialog appearance = ShadowDialog.getLatestDialog();
        ViewGroup chips = appearance.findViewById(R.id.font_chips);
        assertEquals(2, chips.getChildCount());

        appearance.findViewById(R.id.fonts_button).performClick();
        TestSupport.idle();
        android.app.Dialog manager = ShadowDialog.getLatestDialog();
        LinearLayout row = rowNamed(manager, "Lora");
        row.findViewById(R.id.font_action).performClick();
        TestSupport.idle();

        assertEquals("the reader falls back to the system face", Fonts.SYSTEM,
                new ReaderPrefs(app).font);
        assertEquals("and the chip row follows", 1, chips.getChildCount());
        assertTrue(((Chip) chips.getChildAt(0)).isChecked());
    }
}
