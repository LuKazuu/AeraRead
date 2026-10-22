package com.aera.read.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.aera.read.util.Fonts;

/** Reading settings, shared by every book. */
public class ReaderPrefs {

    private static final String PREFS = "aera_reader";

    public String font = Fonts.SYSTEM;
    public int sizeSp = 18;
    public int linePct = 155;
    public String theme = "paper";
    public String viewMode = "pages"; // "pages" | "scroll"
    public String direction = "ltr";  // "ltr" | "rtl"
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
        keepScreenOn = sp.getBoolean("keep_on", keepScreenOn);
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
                .putBoolean("keep_on", keepScreenOn)
                .apply();
    }
}
