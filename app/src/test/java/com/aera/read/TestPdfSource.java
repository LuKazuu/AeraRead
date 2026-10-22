package com.aera.read;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.aera.read.pdf.PdfPageSource;

import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic stand-in for the platform PDF renderer. */
public class TestPdfSource implements PdfPageSource {

    private final int pages;
    public final AtomicInteger renders = new AtomicInteger();

    public TestPdfSource(int pages) {
        this.pages = pages;
    }

    @Override
    public int pageCount() {
        return pages;
    }

    @Override
    public Bitmap render(int page, int targetWidth) {
        renders.incrementAndGet();
        Bitmap bitmap = Bitmap.createBitmap(targetWidth, (int) (targetWidth * 1.4f),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(targetWidth / 12f);
        canvas.drawText("Page " + (page + 1), targetWidth / 10f, targetWidth / 5f, paint);
        return bitmap;
    }

    @Override
    public void close() {
    }
}
