package com.aera.read.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.aera.read.R;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.util.FontCatalog;
import com.aera.read.util.FontStore;
import com.aera.read.util.Fonts;
import com.aera.read.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Appearance controls. Sections the current format cannot use (typography and layout for
 * PDFs) are left out entirely instead of being shown disabled.
 */
public class AppearanceSheet {

    public interface Callback {
        /** True when text metrics changed and the book must be re-paginated. */
        void onAppearanceChanged(boolean needsRelayout);

        /** Typography changed while a slider is still under the finger. */
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
        dialog = new BottomSheetDialog(ctx);
        dialog.setContentView(R.layout.sheet_appearance);
        View root = dialog.findViewById(R.id.sheet_root);
        if (root == null) return;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    Ui.dp(ctx, 20) + bars.bottom);
            return insets;
        });

        buildSwatches(root);

        View typography = root.findViewById(R.id.typography_section);
        View layoutSection = root.findViewById(R.id.layout_section);
        View note = root.findViewById(R.id.typography_note);
        typography.setVisibility(supportsTypography ? View.VISIBLE : View.GONE);
        layoutSection.setVisibility(supportsViewMode ? View.VISIBLE : View.GONE);
        note.setVisibility(supportsTypography ? View.GONE : View.VISIBLE);

        sizeValue = root.findViewById(R.id.size_value);
        lineValue = root.findViewById(R.id.line_value);

        if (supportsTypography) buildTypography(root);
        if (supportsViewMode) buildLayout(root);

        MaterialSwitch keepOn = root.findViewById(R.id.keep_on);
        keepOn.setChecked(prefs.keepScreenOn);
        keepOn.setOnCheckedChangeListener((buttonView, isChecked) -> applyKeepScreenOn(isChecked));

        dialog.show();
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

    /** Opens the download list; it reports back here so the chips stay in step. */
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

    /** Mid-drag: store and re-render now, without waiting for the finger to lift. */
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

        for (ReaderTheme theme : ReaderTheme.values()) {
            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            column.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            column.setClickable(true);
            column.setFocusable(true);

            ThemeSwatch swatch = new ThemeSwatch(ctx);
            swatch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)));
            swatch.setTheme(theme);
            swatch.setSelectedState(theme.key.equals(prefs.theme));

            TextView label = new TextView(ctx);
            label.setText(theme.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(labelColor);
            label.setGravity(android.view.Gravity.CENTER);
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
        for (int i = 0; i < swatches.size(); i++) {
            swatches.get(i).setSelectedState(all[i].key.equals(prefs.theme));
        }
    }

    /** Only faces that are on the device get a chip: the system one plus downloads. */
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

    /**
     * The row scrolls sideways. Only move it when the chosen chip is actually out of sight,
     * so opening the sheet never leaves the first face half cut off.
     */
    private void scrollToSelectedFont() {
        View scroll = ((View) fontChips.getParent());
        if (!(scroll instanceof android.widget.HorizontalScrollView)) return;
        int index = 0;
        for (int i = 0; i < fontChips.getChildCount(); i++) {
            if (prefs.font.equals(fontChips.getChildAt(i).getTag())) index = i;
        }
        int target = index;
        scroll.post(() -> {
            android.widget.HorizontalScrollView view =
                    (android.widget.HorizontalScrollView) scroll;
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
