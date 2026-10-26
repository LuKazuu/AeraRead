package com.aera.read.data;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

/**
 * Reader color themes. {@link #LIGHT} applies zero effects; {@link #SEPIA}, {@link #SLATE} and
 * {@link #NIGHT} tint EPUB pages directly and provide hardware-accelerated {@link ColorMatrix}
 * filters when full-page PDF theming is active.
 */
public enum ReaderTheme {
    LIGHT("light", "Light", 0xFFFFFFFF, 0xFF1A1A1A, 0xFF727272, false, false),
    SEPIA("sepia", "Sepia", 0xFFF2E8D5, 0xFF4A3B2A, 0xFFA2906F, false, true),
    SLATE("slate", "Slate", 0xFF24282B, 0xFFD6D9DB, 0xFF7E868C, true, true),
    NIGHT("night", "Night", 0xFF0C0C0D, 0xFFC8C6C1, 0xFF6B6A66, true, true);

    public final String key;
    public final String label;
    public final int bg;
    public final int fg;
    public final int subtle;
    public final boolean dark;
    private final boolean effects;
    private ColorMatrixColorFilter pdfFilter;

    ReaderTheme(String key, String label, int bg, int fg, int subtle, boolean dark,
                boolean effects) {
        this.key = key;
        this.label = label;
        this.bg = bg;
        this.fg = fg;
        this.subtle = subtle;
        this.dark = dark;
        this.effects = effects;
    }

    public static ReaderTheme from(String key) {
        for (ReaderTheme t : values()) {
            if (t.key.equals(key)) return t;
        }
        return LIGHT;
    }

    /**
     * Hardware color filter for full-page PDF theming. Returns {@code null} when only the
     * surrounding background should change or when {@link #LIGHT} is selected (no effects).
     */
    public ColorMatrixColorFilter pdfColorFilter(boolean fullPage) {
        if (!fullPage || !effects) return null;
        if (pdfFilter == null) {
            pdfFilter = new ColorMatrixColorFilter(buildPdfMatrix());
        }
        return pdfFilter;
    }

    ColorMatrix buildPdfMatrix() {
        int bgR = Color.red(bg), bgG = Color.green(bg), bgB = Color.blue(bg);
        int fgR = Color.red(fg), fgG = Color.green(fg), fgB = Color.blue(fg);

        if (!dark) {
            // Warm linear tint: black (0) -> fg, white (255) -> bg.
            float sR = (bgR - fgR) / 255f;
            float sG = (bgG - fgG) / 255f;
            float sB = (bgB - fgB) / 255f;
            return new ColorMatrix(new float[]{
                    sR, 0f, 0f, 0f, bgR - 255f * sR,
                    0f, sG, 0f, 0f, bgG - 255f * sG,
                    0f, 0f, sB, 0f, bgB - 255f * sB,
                    0f, 0f, 0f, 1f, 0f
            });
        }

        // Hue-preserving smart dark inversion: inverts luminance while keeping chromaticity
        // un-inverted so colored links, charts and photos keep natural hues, while white
        // paper maps strictly to theme.bg and black ink maps strictly to theme.fg.
        final float wR = 0.2126f, wG = 0.7152f, wB = 0.0722f;
        final float k = 0.70f;
        final float lum = -(1f + k);

        float dR = (fgR - bgR) / 255f;
        float dG = (fgG - bgG) / 255f;
        float dB = (fgB - bgB) / 255f;

        float r0 = dR * (lum * wR + k), r1 = dR * (lum * wG), r2 = dR * (lum * wB);
        float g0 = dG * (lum * wR), g1 = dG * (lum * wG + k), g2 = dG * (lum * wB);
        float b0 = dB * (lum * wR), b1 = dB * (lum * wG), b2 = dB * (lum * wB + k);

        return new ColorMatrix(new float[]{
                r0, r1, r2, 0f, bgR - 255f * (r0 + r1 + r2),
                g0, g1, g2, 0f, bgG - 255f * (g0 + g1 + g2),
                b0, b1, b2, 0f, bgB - 255f * (b0 + b1 + b2),
                0f, 0f, 0f, 1f, 0f
        });
    }
}
