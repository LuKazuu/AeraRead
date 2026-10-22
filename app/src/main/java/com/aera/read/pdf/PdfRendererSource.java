package com.aera.read.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.aera.read.data.Book;
import com.aera.read.io.BookFiles;

import java.io.IOException;

/** Rasterizes pages with the platform PdfRenderer, straight from the document's descriptor. */
public class PdfRendererSource implements PdfPageSource {

    static final int MAX_WIDTH = 2400;

    interface Backend {
        int pageCount();

        void renderPage(int page, int width, BitmapAllocator allocator, BitmapConsumer consumer)
                throws IOException;

        void close();
    }

    interface BitmapAllocator {
        Bitmap allocate(int width, float ratio) throws IOException;
    }

    interface BitmapConsumer {
        void accept(Bitmap bitmap);
    }

    private final ParcelFileDescriptor descriptor;
    private final Backend backend;
    private final int count;
    private boolean closed;

    public static PdfPageSource open(Context context, Book book) throws IOException {
        ParcelFileDescriptor pfd = BookFiles.openDescriptor(context, book);
        try {
            final PdfRenderer renderer = new PdfRenderer(pfd);
            return new PdfRendererSource(pfd, new Backend() {
                @Override
                public int pageCount() {
                    return renderer.getPageCount();
                }

                @Override
                public void renderPage(int page, int width, BitmapAllocator allocator,
                                       BitmapConsumer consumer) throws IOException {
                    try (PdfRenderer.Page p = renderer.openPage(page)) {
                        float ratio = p.getHeight() / (float) Math.max(1, p.getWidth());
                        Bitmap bitmap = allocator.allocate(width, ratio);
                        bitmap.eraseColor(Color.WHITE);
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        consumer.accept(bitmap);
                    }
                }

                @Override
                public void close() {
                    renderer.close();
                }
            });
        } catch (IOException | RuntimeException e) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Cannot open PDF", e);
        }
    }

    PdfRendererSource(ParcelFileDescriptor pfd, Backend backend) {
        this.descriptor = pfd;
        this.backend = backend;
        this.count = backend.pageCount();
    }

    @Override
    public synchronized int pageCount() {
        return closed ? 0 : count;
    }

    @Override
    public synchronized Bitmap render(int page, int targetWidth) throws IOException {
        if (closed) throw new IOException("closed");
        if (page < 0 || page >= count) return null;
        int width = Math.max(200, Math.min(targetWidth, MAX_WIDTH));
        final Bitmap[] out = new Bitmap[1];
        try {
            backend.renderPage(page, width, PdfRendererSource::allocateBitmap, b -> out[0] = b);
            return out[0];
        } catch (RuntimeException e) {
            throw new IOException("Render failed", e);
        }
    }

    static Bitmap allocateBitmap(int width, float ratio) throws IOException {
        try {
            int height = Math.max(1, Math.round(width * ratio));
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            int fallbackWidth = Math.max(200, width / 2);
            int fallbackHeight = Math.max(1, Math.round(fallbackWidth * ratio));
            try {
                return Bitmap.createBitmap(fallbackWidth, fallbackHeight,
                        Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError fatal) {
                throw new IOException("Out of memory rendering PDF page", fatal);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            backend.close();
        } catch (Exception ignored) {
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Exception ignored) {
            }
        }
    }
}
