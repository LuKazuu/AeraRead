package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ThumbnailsTest {

    private static void assertColor(int expected, int actual, int tolerance) {
        assertEquals("red", Color.red(expected), Color.red(actual), tolerance);
        assertEquals("green", Color.green(expected), Color.green(actual), tolerance);
        assertEquals("blue", Color.blue(expected), Color.blue(actual), tolerance);
    }

    private Bitmap coverWithTransparentMargin() {
        Bitmap cover = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(180, 40, 40));
        canvas.drawRect(100, 100, 500, 700, paint);
        return cover;
    }

    @Test
    public void transparentMarginsBecomePaperNotBlack() {
        Application app = ApplicationProvider.getApplicationContext();
        String path = Thumbnails.save(app, "content://covers/alpha", coverWithTransparentMargin());
        assertTrue(path.endsWith(".jpg"));

        Bitmap saved = Thumbnails.decode(path, 500);
        assertNotNull(saved);
        assertEquals("no black bar in the corner", Color.WHITE, saved.getPixel(2, 2));
        assertEquals(Color.WHITE, saved.getPixel(saved.getWidth() - 3, saved.getHeight() - 3));
        assertColor(Color.rgb(180, 40, 40),
                saved.getPixel(saved.getWidth() / 2, saved.getHeight() / 2), 6);
    }

    @Test
    public void opaqueCoversKeepTheirColor() {
        Application app = ApplicationProvider.getApplicationContext();
        Bitmap cover = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawColor(Color.rgb(20, 90, 60));
        Bitmap saved = Thumbnails.decode(
                Thumbnails.save(app, "content://covers/opaque", cover), 500);
        assertNotNull(saved);
        assertColor(Color.rgb(20, 90, 60), saved.getPixel(1, 1), 6);
    }

    @Test
    public void decodeOfAMissingFileIsNull() {
        assertEquals(null, Thumbnails.decode("", 500));
        assertEquals(null, Thumbnails.decode("/no/such/cover.jpg", 500));
    }
}
