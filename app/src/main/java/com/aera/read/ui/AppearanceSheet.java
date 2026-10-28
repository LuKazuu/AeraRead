package com.aera.read.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aera.read.R;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.util.FontCatalog;
import com.aera.read.util.FontStore;
import com.aera.read.util.Fonts;
import com.aera.read.util.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;

/**
 * Appearance controls. Adapts cleanly between EPUB (typography, line spacing, scroll/paged
 * layout) and PDF (full-page vs background-only theme scope).
 */
public class AppearanceSheet {

    public interface Callback {
        void onAppearanceChanged(boolean needsRelayout);

        void onAppearancePreview();
    }

    private final Context ctx;
    private final ReaderPrefs prefs;
    private final boolean supportsTypography;
    private final boolean supportsViewMode;
    private final Callback callback;
    private BottomSheetDialog dialog;
    private final List<ThemeSwatch> swatches = new ArrayList<>();
    private TextView sizeValue;
    private TextView lineValue;
    private LinearLayout directionRow;
    private ChipGroup fontChips;
    private boolean previewed;

    public AppearanceSheet(Context ctx, ReaderPrefs prefs, boolean supportsTypography,
                           boolean supportsViewMode, Callback callback) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.supportsTypography = supportsTypography;
        this.supportsViewMode = supportsViewMode;
        this.callback = callback;
    }

    public void show() {
        dialog = Ui.createSheet(ctx, R.layout.sheet_appearance, 20);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root == null) return;

        buildSwatches(root);

        View pdfThemeSection = root.findViewById(R.id.pdf_theme_section);
        View typography = root.findViewById(R.id.typography_section);
        View layoutSection = root.findViewById(R.id.layout_section);

        pdfThemeSection.setVisibility(supportsTypography ? View.GONE : View.VISIBLE);
        typography.setVisibility(supportsTypography ? View.VISIBLE : View.GONE);
        layoutSection.setVisibility(supportsViewMode ? View.VISIBLE : View.GONE);

        sizeValue = root.findViewById(R.id.size_value);
        lineValue = root.findViewById(R.id.line_value);

        if (!supportsTypography) buildPdfThemeScope(root);
        if (supportsTypography) buildTypography(root);
        if (supportsViewMode) buildLayout(root);

        MaterialSwitch tapToTurn = root.findViewById(R.id.tap_to_turn);
        tapToTurn.setChecked(prefs.tapToTurn);
        tapToTurn.setOnCheckedChangeListener(
                (buttonView, isChecked) -> applyTapToTurn(isChecked));

        MaterialSwitch keepOn = root.findViewById(R.id.keep_on);
        keepOn.setChecked(prefs.keepScreenOn);
        keepOn.setOnCheckedChangeListener((buttonView, isChecked) -> applyKeepScreenOn(isChecked));

        dialog.show();
    }

    private void buildPdfThemeScope(View root) {
        MaterialButtonToggleGroup toggle = root.findViewById(R.id.pdf_theme_toggle);
        if (toggle != null) {
            toggle.check(prefs.pdfFullTheme ? R.id.btn_pdf_full : R.id.btn_pdf_bg);
            toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                applyPdfFullTheme(checkedId == R.id.btn_pdf_full);
            });
        }

        MaterialButtonToggleGroup trimToggle = root.findViewById(R.id.pdf_trim_toggle);
        if (trimToggle != null) {
            String activeTrim = ReaderPrefs.normalizeTrim(prefs.pdfTrim);
            int checkedTrimId = ReaderPrefs.TRIM_OFF.equals(activeTrim)
                    ? R.id.btn_trim_off
                    : (ReaderPrefs.TRIM_MAX.equals(activeTrim)
                    ? R.id.btn_trim_max : R.id.btn_trim_normal);
            trimToggle.check(checkedTrimId);
            trimToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                if (checkedId == R.id.btn_trim_off) applyPdfTrim(ReaderPrefs.TRIM_OFF);
                else if (checkedId == R.id.btn_trim_max) applyPdfTrim(ReaderPrefs.TRIM_MAX);
                else applyPdfTrim(ReaderPrefs.TRIM_NORMAL);
            });
        }
    }

    private void buildTypography(View root) {
        buildFontChips(root);

        View fontsButton = root.findViewById(R.id.fonts_button);
        if (fontsButton != null) {
            fontsButton.setOnClickListener(v -> openFontLibrary(root));
        }

        Slider sizeSlider = root.findViewById(R.id.size_slider);
        sizeSlider.setStepSize(1f);
        sizeSlider.setValue(Math.max(14, Math.min(30, prefs.sizeSp)));
        updateSizeLabel(prefs.sizeSp);
        sizeSlider.addOnChangeListener((slider, value, fromUser) -> {
            int size = Math.round(value);
            updateSizeLabel(size);
            if (fromUser) previewSize(size);
        });
        sizeSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                callback.onAppearancePreview();
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                applySize(Math.round(slider.getValue()));
            }
        });

        Slider lineSlider = root.findViewById(R.id.line_slider);
        lineSlider.setStepSize(5f);
        lineSlider.setValue(Math.max(125, Math.min(190, Math.round(prefs.linePct / 5f) * 5)));
        updateLineLabel(prefs.linePct);
        lineSlider.addOnChangeListener((slider, value, fromUser) -> {
            int pct = Math.round(value);
            updateLineLabel(pct);
            if (fromUser) previewLineSpacing(pct);
        });
        lineSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                callback.onAppearancePreview();
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                applyLineSpacing(Math.round(slider.getValue()));
            }
        });
    }

    private void buildLayout(View root) {
        MaterialButtonToggleGroup viewToggle = root.findViewById(R.id.view_toggle);
        viewToggle.check(prefs.isScroll() ? R.id.btn_scroll : R.id.btn_pages);
        directionRow = root.findViewById(R.id.direction_row);
        directionRow.setVisibility(prefs.isScroll() ? View.GONE : View.VISIBLE);
        viewToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            applyViewMode(checkedId == R.id.btn_scroll ? "scroll" : "pages");
        });

        MaterialButtonToggleGroup directionToggle = root.findViewById(R.id.direction_toggle);
        directionToggle.check(prefs.isRtl() ? R.id.btn_rtl : R.id.btn_ltr);
        directionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            applyDirection(checkedId == R.id.btn_rtl ? "rtl" : "ltr");
        });
    }

    // ------------------------------------------------------------------ actions

    private void openFontLibrary(View root) {
        new FontLibrarySheet(ctx, () -> {
            if (!Fonts.isAvailable(ctx, prefs.font)) {
                prefs.font = Fonts.SYSTEM;
                prefs.save();
                callback.onAppearanceChanged(true);
            }
            buildFontChips(root);
        }).show();
    }

    void applyFont(String key) {
        prefs.font = key;
        prefs.save();
        selectFontChip(key);
        callback.onAppearanceChanged(true);
    }

    void previewSize(int sizeSp) {
        int clamped = Math.max(14, Math.min(30, sizeSp));
        if (prefs.sizeSp == clamped) return;
        prefs.sizeSp = clamped;
        prefs.save();
        previewed = true;
        callback.onAppearancePreview();
    }

    void previewLineSpacing(int pct) {
        int clamped = Math.max(125, Math.min(190, pct));
        if (prefs.linePct == clamped) return;
        prefs.linePct = clamped;
        prefs.save();
        previewed = true;
        callback.onAppearancePreview();
    }

    void applySize(int sizeSp) {
        int clamped = Math.max(14, Math.min(30, sizeSp));
        updateSizeLabel(clamped);
        if (prefs.sizeSp == clamped && !previewed) return;
        previewed = false;
        prefs.sizeSp = clamped;
        prefs.save();
        callback.onAppearanceChanged(true);
    }

    void applyLineSpacing(int pct) {
        int clamped = Math.max(125, Math.min(190, pct));
        updateLineLabel(clamped);
        if (prefs.linePct == clamped && !previewed) return;
        previewed = false;
        prefs.linePct = clamped;
        prefs.save();
        callback.onAppearanceChanged(true);
    }

    void applyTheme(String key) {
        prefs.theme = key;
        prefs.save();
        refreshSwatches();
        callback.onAppearanceChanged(false);
    }

    void applyPdfFullTheme(boolean fullPage) {
        if (prefs.pdfFullTheme == fullPage) return;
        prefs.pdfFullTheme = fullPage;
        prefs.save();
        callback.onAppearanceChanged(false);
    }

    void applyPdfTrim(String mode) {
        String normalized = ReaderPrefs.normalizeTrim(mode);
        if (normalized.equals(ReaderPrefs.normalizeTrim(prefs.pdfTrim))) return;
        prefs.pdfTrim = normalized;
        prefs.save();
        callback.onAppearanceChanged(true);
    }

    void applyViewMode(String mode) {
        if (prefs.viewMode.equals(mode)) return;
        prefs.viewMode = mode;
        prefs.save();
        if (directionRow != null) {
            directionRow.setVisibility(prefs.isScroll() ? View.GONE : View.VISIBLE);
        }
        callback.onAppearanceChanged(false);
    }

    void applyDirection(String direction) {
        if (prefs.direction.equals(direction)) return;
        prefs.direction = direction;
        prefs.save();
        callback.onAppearanceChanged(false);
    }

    /**
     * Toggles the opt-in edge tap zones. Applies to Pages and Scroll mode alike; the reader
     * picks the new value up through {@code onAppearanceChanged} without a relayout.
     */
    void applyTapToTurn(boolean enabled) {
        if (prefs.tapToTurn == enabled) return;
        prefs.tapToTurn = enabled;
        prefs.save();
        callback.onAppearanceChanged(false);
    }

    void applyKeepScreenOn(boolean keepOn) {
        prefs.keepScreenOn = keepOn;
        prefs.save();
        callback.onAppearanceChanged(false);
    }

    // ------------------------------------------------------------------ builders

    private void buildSwatches(View root) {
        LinearLayout row = root.findViewById(R.id.theme_row);
        if (row == null) return;
        row.removeAllViews();
        swatches.clear();
        int labelColor = Ui.colorAttr(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        String activeKey = ReaderTheme.from(prefs.theme).key;

        for (ReaderTheme theme : ReaderTheme.values()) {
            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            column.setClickable(true);
            column.setFocusable(true);

            ThemeSwatch swatch = new ThemeSwatch(ctx);
            swatch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)));
            swatch.setTheme(theme);
            swatch.setSelectedState(theme.key.equals(activeKey));

            TextView label = new TextView(ctx);
            label.setText(theme.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(labelColor);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(ctx, 7), 0, Ui.dp(ctx, 4));

            column.addView(swatch);
            column.addView(label);
            column.setOnClickListener(v -> applyTheme(theme.key));
            row.addView(column);
            swatches.add(swatch);
        }
    }

    private void refreshSwatches() {
        ReaderTheme[] all = ReaderTheme.values();
        String activeKey = ReaderTheme.from(prefs.theme).key;
        for (int i = 0; i < swatches.size(); i++) {
            swatches.get(i).setSelectedState(all[i].key.equals(activeKey));
        }
    }

    private void buildFontChips(View root) {
        fontChips = root.findViewById(R.id.font_chips);
        if (fontChips == null) return;
        fontChips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);

        List<String> ids = new ArrayList<>();
        ids.add(Fonts.SYSTEM);
        for (FontCatalog.Entry entry : FontCatalog.ALL) {
            if (FontStore.isInstalled(ctx, entry.id)) ids.add(entry.id);
        }
        String selected = ids.contains(prefs.font) ? prefs.font : Fonts.SYSTEM;

        for (String id : ids) {
            Chip chip = (Chip) inflater.inflate(R.layout.chip_font, fontChips, false);
            chip.setId(View.generateViewId());
            chip.setText(FontCatalog.label(id));
            chip.setTypeface(Fonts.get(ctx, id, false, false));
            chip.setTag(id);
            chip.setChecked(id.equals(selected));
            chip.setChipBackgroundColor(ColorStateList.valueOf(
                    Ui.colorAttr(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh)));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (checked) applyFont((String) button.getTag());
            });
            fontChips.addView(chip);
        }
        scrollToSelectedFont();
    }

    private void scrollToSelectedFont() {
        View scroll = (View) fontChips.getParent();
        if (!(scroll instanceof HorizontalScrollView)) return;
        int index = 0;
        for (int i = 0; i < fontChips.getChildCount(); i++) {
            if (prefs.font.equals(fontChips.getChildAt(i).getTag())) index = i;
        }
        int target = index;
        scroll.post(() -> {
            HorizontalScrollView view = (HorizontalScrollView) scroll;
            View chip = fontChips.getChildAt(target);
            if (chip == null || view.getWidth() == 0) return;
            int left = chip.getLeft();
            int right = chip.getRight();
            int visibleLeft = view.getScrollX();
            int visibleRight = visibleLeft + view.getWidth();
            if (left >= visibleLeft && right <= visibleRight) return;
            if (left < visibleLeft) {
                view.smoothScrollTo(left, 0);
            } else {
                view.smoothScrollTo(Math.max(0, right - view.getWidth()), 0);
            }
        });
    }

    private void selectFontChip(String key) {
        if (fontChips == null) return;
        scrollToSelectedFont();
        for (int i = 0; i < fontChips.getChildCount(); i++) {
            View child = fontChips.getChildAt(i);
            if (key.equals(child.getTag()) && child instanceof Chip
                    && !((Chip) child).isChecked()) {
                ((Chip) child).setChecked(true);
            }
        }
    }

    private void updateSizeLabel(int size) {
        if (sizeValue != null) sizeValue.setText(String.valueOf(size));
    }

    private void updateLineLabel(int percent) {
        if (lineValue != null) lineValue.setText(ctx.getString(R.string.percent, percent));
    }
}
