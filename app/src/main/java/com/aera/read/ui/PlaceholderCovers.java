package com.aera.read.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.LruCache;

import com.aera.read.data.Book;
import com.aera.read.util.Ui;

import java.util.Locale;

/** Generated fallback covers for books without cover art. */
public final class PlaceholderCovers {

    private static final int[] COLORS = {
            0xFF3F5D4C, 0xFF5B4A3F, 0xFF44525E, 0xFF5C4B58, 0xFF4F5A3A, 0xFF6A4B41
    };
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(16);

    private PlaceholderCovers() {
    }

    public static Bitmap get(Book book) {
        String key = book.id() + "|" + book.title;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = draw(book);
        CACHE.put(key, bitmap);
        return bitmap;
    }

    public static void trimMemory(int level) {
        Ui.trimCache(CACHE, level);
    }

    private static Bitmap draw(Book book) {
        int width = 480;
        int height = 640;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        int bg = COLORS[Math.floorMod(book.id().hashCode(), COLORS.length)];
        canvas.drawColor(bg);

        Paint deco = new Paint(Paint.ANTI_ALIAS_FLAG);
        deco.setStyle(Paint.Style.STROKE);
        deco.setStrokeWidth(3);
        deco.setColor(Color.argb(48, 255, 255, 255));
        canvas.drawCircle(width * 0.9f, height * 0.12f, width * 0.52f, deco);
        canvas.drawCircle(width * 0.05f, height * 0.95f, width * 0.38f, deco);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.argb(235, 255, 255, 255));
        text.setTypeface(Typeface.create("serif", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(148);
        canvas.drawText(initials(book.title), width / 2f, height * 0.46f, text);

        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setColor(Color.argb(170, 255, 255, 255));
        small.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(26);
        small.setLetterSpacing(0.25f);
        canvas.drawText(book.isPdf() ? "PDF" : "EPUB", width / 2f, height * 0.58f, small);

        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(Color.argb(120, 255, 255, 255));
        canvas.drawRect(width * 0.44f, height * 0.66f, width * 0.56f, height * 0.66f + 2, rule);
        return bitmap;
    }

    private static String initials(String title) {
        if (title == null || title.trim().isEmpty()) return "?";
        String trimmed = title.trim();
        StringBuilder sb = new StringBuilder(2);
        boolean wordStart = true;
        for (int i = 0; i < trimmed.length() && sb.length() < 2; i++) {
            char c = trimmed.charAt(i);
            if (c <= ' ') {
                wordStart = true;
            } else if (wordStart) {
                wordStart = false;
                char up = Character.toUpperCase(c);
                if (Character.isLetterOrDigit(up)) sb.append(up);
            }
        }
        if (sb.length() == 0) sb.append(trimmed.toUpperCase(Locale.ROOT).charAt(0));
        return sb.toString();
    }
}
