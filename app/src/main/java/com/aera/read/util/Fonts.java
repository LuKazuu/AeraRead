package com.aera.read.util;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns a stored font key into a typeface. The system font is always available; any other
 * key resolves only while its downloaded file exists, so a removed font degrades to the
 * system face instead of showing missing text.
 */
public final class Fonts {

    public static final String SYSTEM = "system";

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private static int weight(boolean bold) {
        return bold ? 700 : 400;
    }

    private Fonts() {
    }

    public static Typeface get(Context context, String key, boolean bold, boolean italic) {
        int style = bold && italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD
                : italic ? Typeface.ITALIC : Typeface.NORMAL;
        if (key == null || SYSTEM.equals(key)) return Typeface.create("sans-serif", style);

        String cacheKey = key + "|" + style;
        synchronized (CACHE) {
            Typeface cached = CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        Typeface loaded = italic && !bold
                ? Typeface.create(get(context, key, false, false), Typeface.ITALIC)
                : load(key, context, bold, italic, style);
        synchronized (CACHE) {
            CACHE.put(cacheKey, loaded);
        }
        return loaded;
    }

    /** True when this key will render as stored: the system face, or an existing file. */
    public static boolean isAvailable(Context context, String key) {
        if (key == null || SYSTEM.equals(key)) return true;
        return FontStore.isInstalled(context, key);
    }

    /** Called after a font is installed or removed, so the next lookup rereads the disk. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static Typeface load(String key, Context context, boolean bold, boolean italic,
                                 int style) {
        File file = FontStore.file(context, key);
        if (!file.isFile()) return Typeface.create("sans-serif", style);

        Typeface weighted = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                weighted = new Typeface.Builder(file)
                        .setFontVariationSettings("'wght' " + weight(bold))
                        .build();
            } catch (Exception ignored) {
            }
        }
        if (weighted == null) {
            try {
                weighted = Typeface.createFromFile(file);
            } catch (Exception e) {
                return Typeface.create("sans-serif", style);
            }
        }
        if (weighted == null) return Typeface.create("sans-serif", style);
        return italic ? Typeface.create(weighted, Typeface.ITALIC) : weighted;
    }
}
