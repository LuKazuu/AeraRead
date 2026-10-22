package com.aera.read.data;

/** Reader color themes. Values taken from the app's warm reading palette. */
public enum ReaderTheme {
    PAPER("paper", "Paper", 0xFFFAF7F0, 0xFF26231D, 0xFFA19B8E, false),
    SEPIA("sepia", "Sepia", 0xFFF2E8D5, 0xFF4A3B2A, 0xFFA2906F, false),
    SLATE("slate", "Slate", 0xFF24282B, 0xFFD6D9DB, 0xFF7E868C, true),
    NIGHT("night", "Night", 0xFF0C0C0D, 0xFFC8C6C1, 0xFF6B6A66, true);

    public final String key;
    public final String label;
    public final int bg;
    public final int fg;
    public final int subtle;
    public final boolean dark;

    ReaderTheme(String key, String label, int bg, int fg, int subtle, boolean dark) {
        this.key = key;
        this.label = label;
        this.bg = bg;
        this.fg = fg;
        this.subtle = subtle;
        this.dark = dark;
    }

    public static ReaderTheme from(String key) {
        for (ReaderTheme t : values()) {
            if (t.key.equals(key)) return t;
        }
        return PAPER;
    }
}
