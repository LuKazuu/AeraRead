package com.aera.read.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Tiny cover thumbnails kept in the cache directory. These are the only app-side files a
 * folder book produces; the document itself is never copied.
 */
public final class Thumbnails {

    private Thumbnails() {
    }

    public static String save(Context ctx, String bookUri, Bitmap bitmap) {
        try {
            File dir = new File(ctx.getCacheDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return "";
            File file = new File(dir, name(bookUri));
            int width = Math.min(360, bitmap.getWidth());
            int height = Math.max(1, Math.round(bitmap.getHeight() * (width
                    / (float) bitmap.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            // JPEG has no alpha: transparent cover margins would turn into black bars.
            Bitmap flattened = scaled;
            if (scaled.hasAlpha()) {
                flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(flattened);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(scaled, 0, 0, null);
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                flattened.compress(Bitmap.CompressFormat.JPEG, 86, out);
            }
            if (flattened != scaled) flattened.recycle();
            if (scaled != bitmap) scaled.recycle();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    public static Bitmap decode(String path, int maxWidth) {
        return decode(path, maxWidth, Bitmap.Config.ARGB_8888);
    }

    /** Decodes an opaque JPEG thumbnail in RGB_565 for half the RAM per library card. */
    public static Bitmap decodeCard(String path, int maxWidth) {
        return decode(path, maxWidth, Bitmap.Config.RGB_565);
    }

    private static Bitmap decode(String path, int maxWidth, Bitmap.Config config) {
        if (path == null || path.isEmpty()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = config;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError | Exception e) {
            return null;
        }
    }

    private static String name(String bookUri) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(bookUri.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            return sb + ".jpg";
        } catch (Exception e) {
            return Integer.toHexString(bookUri.hashCode()) + ".jpg";
        }
    }
}
