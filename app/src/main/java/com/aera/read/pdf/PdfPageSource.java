package com.aera.read.pdf;

import android.content.Context;
import android.graphics.Bitmap;

import com.aera.read.data.Book;

import java.io.Closeable;
import java.io.IOException;

/** Abstraction over PDF rasterization so tests can supply deterministic page images. */
public interface PdfPageSource extends Closeable {

    int pageCount();

    Bitmap render(int page, int targetWidth) throws IOException;

    default void setTrimMode(String trimMode) {
    }

    interface Factory {
        PdfPageSource open(Context context, Book book) throws IOException;
    }
}
