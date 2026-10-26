package com.aera.read.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.aera.read.util.Fonts;

/** Reading settings, shared across every book. */
public final class ReaderPrefs {

    private static final String PREFS = "aera_reader";

    public static final String TRIM_OFF = "off";
    public static final String TRIM_NORMAL = "normal";
    public static final String TRIM_MAX = "max";

    public String font = Fonts.SYSTEM;
    public int sizeSp = 18;
    public int linePct = 155;
    public String theme = ReaderTheme.LIGHT.key;
    public String viewMode = "pages"; // "pages" | "scroll"
    public String direction = "ltr";  // "ltr" | "rtl"
    public boolean pdfFullTheme = true;
    public String pdfTrim = TRIM_NORMAL; // "off" | "normal" | "max"
    public boolean keepScreenOn = false;

    private final SharedPreferences sp;

    public ReaderPrefs(Context c) {
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        font = sp.getString("font", font);
        sizeSp = sp.getInt("size", sizeSp);
        linePct = sp.getInt("line", linePct);
        theme = sp.getString("theme", theme);
        viewMode = sp.getString("view", viewMode);
        direction = sp.getString("direction", direction);
        pdfFullTheme = sp.getBoolean("pdf_full_theme", pdfFullTheme);
        pdfTrim = normalizeTrim(sp.getString("pdf_trim", pdfTrim));
        keepScreenOn = sp.getBoolean("keep_on", keepScreenOn);
    }

    public static String normalizeTrim(String mode) {
        if (TRIM_OFF.equals(mode)) return TRIM_OFF;
        if (TRIM_MAX.equals(mode)) return TRIM_MAX;
        return TRIM_NORMAL;
    }

    public boolean isMaxTrim() {
        return TRIM_MAX.equals(normalizeTrim(pdfTrim));
    }

    public boolean isScroll() {
        return "scroll".equals(viewMode);
    }

    public boolean isRtl() {
        return "rtl".equals(direction);
    }

    public void save() {
        sp.edit()
                .putString("font", font)
                .putInt("size", sizeSp)
                .putInt("line", linePct)
                .putString("theme", theme)
                .putString("view", viewMode)
                .putString("direction", direction)
                .putBoolean("pdf_full_theme", pdfFullTheme)
                .putString("pdf_trim", normalizeTrim(pdfTrim))
                .putBoolean("keep_on", keepScreenOn)
                .apply();
    }
}
