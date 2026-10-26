package com.aera.read.util;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves font keys to Android 9+ {@link Typeface} instances. Uses {@link Typeface.Builder}
 * for variable OpenType weights and API 28+ {@link Typeface#create(Typeface, int, boolean)}
 * for exact weight/italic synthesis with zero legacy fallback branches.
 */
public final class Fonts {

    public static final String SYSTEM = "system";

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private Fonts() {
    }

    private static int weight(boolean bold) {
        return bold ? 700 : 400;
    }

    public static Typeface get(Context context, String key, boolean bold, boolean italic) {
        int w = weight(bold);
        if (key == null || SYSTEM.equals(key)) {
            String sysKey = "sys|" + w + "|" + italic;
            synchronized (CACHE) {
                Typeface cached = CACHE.get(sysKey);
                if (cached != null) return cached;
                Typeface created = Typeface.create(Typeface.SANS_SERIF, w, italic);
                CACHE.put(sysKey, created);
                return created;
            }
        }

        String cacheKey = key + "|" + w + "|" + italic;
        synchronized (CACHE) {
            Typeface cached = CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        Typeface loaded = load(key, context, w, italic);
        synchronized (CACHE) {
            CACHE.put(cacheKey, loaded);
        }
        return loaded;
    }

    public static Typeface mono(boolean bold, boolean italic) {
        int w = weight(bold);
        String key = "mono|" + w + "|" + italic;
        synchronized (CACHE) {
            Typeface cached = CACHE.get(key);
            if (cached != null) return cached;
            Typeface created = Typeface.create(Typeface.MONOSPACE, w, italic);
            CACHE.put(key, created);
            return created;
        }
    }

    /** True when this key will render as stored: the system face, or an existing file. */
    public static boolean isAvailable(Context context, String key) {
        return key == null || SYSTEM.equals(key) || FontStore.isInstalled(context, key);
    }

    /** Called after a font is installed or removed, so the next lookup rereads the disk. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static Typeface load(String key, Context context, int weight, boolean italic) {
        File file = FontStore.file(context, key);
        if (!file.isFile()) return Typeface.create(Typeface.SANS_SERIF, weight, italic);

        Typeface face = null;
        try {
            face = new Typeface.Builder(file)
                    .setFontVariationSettings("'wght' " + weight)
                    .setWeight(weight)
                    .setItalic(italic)
                    .build();
        } catch (Exception ignored) {
        }
        if (face == null) {
            try {
                Typeface raw = Typeface.createFromFile(file);
                if (raw != null) face = Typeface.create(raw, weight, italic);
            } catch (Exception ignored) {
            }
        }
        return face != null ? face : Typeface.create(Typeface.SANS_SERIF, weight, italic);
    }
}
