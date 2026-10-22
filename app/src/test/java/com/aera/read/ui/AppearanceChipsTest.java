package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.util.Fonts;
import com.google.android.material.chip.Chip;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

/** Tests Appearance sheet controls: theme swatch selection ring, font header copy, and chips. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class AppearanceChipsTest {

    private Application app;
    private Context themed;
    private ReaderPrefs prefs;
    private boolean relayout;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        themed = new ContextThemeWrapper(app, R.style.Theme_Aera);
        prefs = new ReaderPrefs(app);
        prefs.font = Fonts.SYSTEM;
        prefs.viewMode = "pages";
        prefs.theme = "paper";
        prefs.save();
        relayout = false;
    }

    private Dialog openDialog(Context context) {
        AppearanceSheet sheet = new AppearanceSheet(context, prefs, true, true,
                new AppearanceSheet.Callback() {
                    @Override
                    public void onAppearanceChanged(boolean needsRelayout) {
                        relayout = true;
                    }

                    @Override
                    public void onAppearancePreview() {
                    }
                });
        sheet.show();
        return ShadowDialog.getLatestDialog();
    }

    private ViewGroup open() {
        return openDialog(themed).findViewById(R.id.font_chips);
    }

    private List<String> labels(ViewGroup chips) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < chips.getChildCount(); i++) {
            out.add(((Chip) chips.getChildAt(i)).getText().toString());
        }
        return out;
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    public void selectedThemeOuterRingIsPureWhiteForPaperSepiaSlateAndNightInDarkMode() {
        Context nightCtx = new ContextThemeWrapper(app, R.style.Theme_Aera);
        int bg = 0xFF171C18;
        for (ReaderTheme rt : ReaderTheme.values()) {
            ThemeSwatch swatch = new ThemeSwatch(nightCtx);
            swatch.setTheme(rt);
            swatch.setSelectedState(true);
            swatch.measure(View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY));
            swatch.layout(0, 0, 88, 88);

            Bitmap bmp = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(bmp);
                c.drawColor(bg);
                swatch.draw(c);
                int px = bmp.getPixel(44, 2);
                assertTrue("Expected bright white outer ring on " + rt.key
                                + ", got #" + Integer.toHexString(px),
                        Color.red(px) >= 235 && Color.green(px) >= 235 && Color.blue(px) >= 235);
            } finally {
                bmp.recycle();
            }

            swatch.setSelectedState(false);
            Bitmap unBmp = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(unBmp);
                c.drawColor(bg);
                swatch.draw(c);
                assertEquals(bg, unBmp.getPixel(44, 2));
            } finally {
                unBmp.recycle();
            }
        }
    }

    @Test
    public void selectedThemeOuterRingContrastsCleanlyInLightMode() {
        int bg = 0xFFF1F5EE;
        for (ReaderTheme rt : ReaderTheme.values()) {
            ThemeSwatch swatch = new ThemeSwatch(themed);
            swatch.setTheme(rt);
            swatch.setSelectedState(true);
            swatch.measure(View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY));
            swatch.layout(0, 0, 88, 88);

            Bitmap bmp = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(bmp);
                c.drawColor(bg);
                swatch.draw(c);
                int px = bmp.getPixel(44, 2);
                assertTrue("Expected crisp dark outer ring on light sheet for " + rt.key
                                + ", got #" + Integer.toHexString(px),
                        Color.red(px) <= 60 && Color.green(px) <= 60 && Color.blue(px) <= 60);
            } finally {
                bmp.recycle();
            }
        }
    }

    @Test
    public void fontSectionShowsFontLabelAndManageActionNotDuplicateFontsLabel() {
        Dialog d = openDialog(themed);
        TextView manageBtn = d.findViewById(R.id.fonts_button);
        assertNotNull(manageBtn);
        assertEquals("Manage", manageBtn.getText().toString());
        LinearLayout headerRow = (LinearLayout) manageBtn.getParent();
        TextView sectionLabel = (TextView) headerRow.getChildAt(0);
        assertEquals("Font", sectionLabel.getText().toString());

        manageBtn.performClick();
        Dialog fontSheet = ShadowDialog.getLatestDialog();
        TextView sheetTitle = fontSheet.findViewById(R.id.sheet_title);
        assertEquals("Fonts", sheetTitle.getText().toString());
    }

    @Test
    public void onlyTheSystemFaceIsOfferedBeforeAnythingIsDownloaded() {
        ViewGroup chips = open();
        assertEquals(1, chips.getChildCount());
        assertEquals(Fonts.SYSTEM, chips.getChildAt(0).getTag());
        assertTrue(((Chip) chips.getChildAt(0)).isChecked());
    }

    @Test
    public void downloadedFontsJoinTheRowInCatalogOrder() throws Exception {
        TestSupport.installFont(app, "notosc");
        TestSupport.installFont(app, "inter");
        TestSupport.installFont(app, "lora");
        ViewGroup chips = open();
        assertEquals(4, chips.getChildCount());
        assertEquals("System", labels(chips).get(0));
        assertEquals("Inter", labels(chips).get(1));
        assertEquals("Lora", labels(chips).get(2));
        assertEquals("Noto Sans SC", labels(chips).get(3));
    }

    @Test
    public void aSelectedDownloadedFaceIsTheCheckedChip() throws Exception {
        TestSupport.installFont(app, "lora");
        prefs.font = "lora";
        prefs.save();
        ViewGroup chips = open();
        assertEquals("lora", chips.getChildAt(1).getTag());
        assertFalse(((Chip) chips.getChildAt(0)).isChecked());
        assertTrue(((Chip) chips.getChildAt(1)).isChecked());
    }

    @Test
    public void tappingAChipRepaintsWithTheNewFace() throws Exception {
        TestSupport.installFont(app, "lora");
        ViewGroup chips = open();
        Chip lora = (Chip) chips.getChildAt(1);
        lora.performClick();
        assertEquals("lora", prefs.font);
        assertEquals(new ReaderPrefs(app).font, "lora");
        assertTrue("a font change repaginates", relayout);
        assertTrue(lora.isChecked());
    }

    @Test
    public void aStoredFontWithoutAFileFallsBackToTheSystemChip() {
        prefs.font = "inter";
        prefs.save();
        ViewGroup chips = open();
        assertEquals(1, chips.getChildCount());
        assertTrue("the system chip is shown as chosen",
                ((Chip) chips.getChildAt(0)).isChecked());
        assertNotNull(chips.getChildAt(0));
    }
}
