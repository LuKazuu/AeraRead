package com.aera.read.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.io.BookFiles;

import java.io.IOException;

/**
 * Rasterizes PDF pages via {@link PdfRenderer} with configurable outer-margin trimming
 * ({@code off}, {@code normal}, {@code max}) and uniform vector sub-region scaling so text and
 * diagrams fill the viewport crisply without squishing or clipping a single glyph or image pixel.
 */
public class PdfRendererSource implements PdfPageSource {

    static final int MAX_WIDTH = 2400;
    private static final int SCOUT_WIDTH = 240;
    private static final int INK_THRESHOLD = 252;
    private static final float BREATHING_PAD_NORMAL = 0.018f;
    private static final float BREATHING_PAD_MAX = 0.002f;
    private static final float MIN_CROP_SPAN_NORMAL = 0.52f;
    private static final float MIN_CROP_SPAN_MAX_X = 0.35f;
    private static final float MIN_CROP_SPAN_MAX_Y = 0.32f;

    /** Vector page rasterizer abstraction (backed by {@link PdfRenderer} in production). */
    public interface PageRasterizer {
        int pageCount();

        int pageWidth(int page) throws IOException;

        int pageHeight(int page) throws IOException;

        void rasterize(int page, Bitmap dest, Matrix transform) throws IOException;

        void close();
    }

    interface Backend {
        int pageCount();

        void renderPage(int page, int width, BitmapAllocator allocator, BitmapConsumer consumer)
                throws IOException;

        default void setTrimMode(String trimMode) {
        }

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
            return new PdfRendererSource(pfd, new AutoCropBackend(new PdfRendererRasterizer(renderer)));
        } catch (IOException | RuntimeException e) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Cannot open PDF", e);
        }
    }

    /** Creates an auto-cropping PDF source over a custom {@link PageRasterizer} (e.g. tests). */
    public static PdfRendererSource fromRasterizer(PageRasterizer rasterizer) {
        return new PdfRendererSource(null, new AutoCropBackend(rasterizer));
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
    public synchronized void setTrimMode(String trimMode) {
        if (!closed) backend.setTrimMode(ReaderPrefs.normalizeTrim(trimMode));
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
            } catch (IOException ignored) {
            }
        }
    }

    // --------------------------------------------------------- native PdfRenderer adapter

    private static final class PdfRendererRasterizer implements PageRasterizer {
        private final PdfRenderer renderer;
        private PdfRenderer.Page openPage;
        private int openIndex = -1;

        PdfRendererRasterizer(PdfRenderer renderer) {
            this.renderer = renderer;
        }

        private PdfRenderer.Page acquire(int page) {
            if (openPage != null && openIndex == page) return openPage;
            releasePage();
            openPage = renderer.openPage(page);
            openIndex = page;
            return openPage;
        }

        void releasePage() {
            if (openPage != null) {
                try {
                    openPage.close();
                } catch (Exception ignored) {
                }
                openPage = null;
                openIndex = -1;
            }
        }

        @Override
        public int pageCount() {
            return renderer.getPageCount();
        }

        @Override
        public int pageWidth(int page) {
            return Math.max(1, acquire(page).getWidth());
        }

        @Override
        public int pageHeight(int page) {
            return Math.max(1, acquire(page).getHeight());
        }

        @Override
        public void rasterize(int page, Bitmap dest, Matrix transform) {
            acquire(page).render(dest, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }

        @Override
        public void close() {
            releasePage();
            renderer.close();
        }
    }

    // --------------------------------------------------------- two-stage margin auto-crop

    static final class AutoCropBackend implements Backend {
        private static final float[] FULL_PAGE = {0f, 0f, 1f, 1f};

        private final PageRasterizer rasterizer;
        private final float[][] rawInk;
        private String trimMode = ReaderPrefs.TRIM_NORMAL;
        private int[] scoutPixels;
        private final Matrix matrix = new Matrix();

        AutoCropBackend(PageRasterizer rasterizer) {
            this.rasterizer = rasterizer;
            this.rawInk = new float[Math.max(0, rasterizer.pageCount())][];
        }

        @Override
        public int pageCount() {
            return rasterizer.pageCount();
        }

        @Override
        public void setTrimMode(String mode) {
            this.trimMode = ReaderPrefs.normalizeTrim(mode);
        }

        @Override
        public void renderPage(int page, int width, BitmapAllocator allocator,
                               BitmapConsumer consumer) throws IOException {
            try {
                int pw = Math.max(1, rasterizer.pageWidth(page));
                int ph = Math.max(1, rasterizer.pageHeight(page));
                float[] crop = resolveCropBounds(page, pw, ph);

                float left = crop[0], top = crop[1], right = crop[2], bottom = crop[3];
                if (left <= 0.005f && top <= 0.005f && right >= 0.995f && bottom >= 0.995f) {
                    Bitmap bmp = allocator.allocate(width, ph / (float) pw);
                    bmp.eraseColor(Color.WHITE);
                    matrix.setScale(bmp.getWidth() / (float) pw, bmp.getHeight() / (float) ph);
                    rasterizer.rasterize(page, bmp, matrix);
                    consumer.accept(bmp);
                    return;
                }

                float cropW = Math.max(1f, (right - left) * pw);
                float cropH = Math.max(1f, (bottom - top) * ph);
                float ratio = cropH / cropW;
                Bitmap bmp = allocator.allocate(width, ratio);
                bmp.eraseColor(Color.WHITE);

                // Strictly uniform X/Y scale: zero aspect-ratio distortion or squishing.
                float scale = bmp.getWidth() / cropW;
                matrix.setTranslate(-left * pw, -top * ph);
                matrix.postScale(scale, scale);
                rasterizer.rasterize(page, bmp, matrix);
                consumer.accept(bmp);
            } finally {
                if (rasterizer instanceof PdfRendererRasterizer) {
                    ((PdfRendererRasterizer) rasterizer).releasePage();
                }
            }
        }

        private float[] resolveCropBounds(int page, int pw, int ph) throws IOException {
            if (ReaderPrefs.TRIM_OFF.equals(trimMode)) {
                return FULL_PAGE;
            }
            float[] ink = rawInk[page];
            if (ink == null) {
                ink = detectRawInk(page, pw, ph);
                rawInk[page] = ink;
            }
            if (ink == FULL_PAGE) return FULL_PAGE;

            int sw = SCOUT_WIDTH;
            int sh = Math.max(1, Math.round(sw * (ph / (float) pw)));
            boolean max = ReaderPrefs.TRIM_MAX.equals(trimMode);
            float guard = max ? 1.5f : 1.0f;
            float breathing = max ? BREATHING_PAD_MAX : BREATHING_PAD_NORMAL;
            float padX = (guard / sw) + breathing;
            float padY = (guard / sh) + breathing;

            float left = Math.max(0f, ink[0] - padX);
            float top = Math.max(0f, ink[1] - padY);
            float right = Math.min(1f, ink[2] + padX);
            float bottom = Math.min(1f, ink[3] + padY);

            float[] xRange = ensureMinSpan(left, right,
                    max ? MIN_CROP_SPAN_MAX_X : MIN_CROP_SPAN_NORMAL);
            float[] yRange = ensureMinSpan(top, bottom,
                    max ? MIN_CROP_SPAN_MAX_Y : MIN_CROP_SPAN_NORMAL);
            return new float[]{xRange[0], yRange[0], xRange[1], yRange[1]};
        }

        private float[] detectRawInk(int page, int pw, int ph) throws IOException {
            int sw = SCOUT_WIDTH;
            int sh = Math.max(1, Math.round(sw * (ph / (float) pw)));
            Bitmap scout = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            scout.eraseColor(Color.WHITE);
            matrix.setScale(sw / (float) pw, sh / (float) ph);
            rasterizer.rasterize(page, scout, matrix);

            int total = sw * sh;
            if (scoutPixels == null || scoutPixels.length < total) {
                scoutPixels = new int[total];
            }
            scout.getPixels(scoutPixels, 0, sw, 0, 0, sw, sh);
            scout.recycle();

            int minX = sw, minY = sh, maxX = -1, maxY = -1;
            for (int y = 0; y < sh; y++) {
                int row = y * sw;
                for (int x = 0; x < sw; x++) {
                    int c = scoutPixels[row + x];
                    if (((c >> 16) & 0xFF) < INK_THRESHOLD
                            || ((c >> 8) & 0xFF) < INK_THRESHOLD
                            || (c & 0xFF) < INK_THRESHOLD) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < minX || maxY < minY) {
                return FULL_PAGE;
            }
            return new float[]{
                    minX / (float) sw,
                    minY / (float) sh,
                    (maxX + 1) / (float) sw,
                    (maxY + 1) / (float) sh
            };
        }

        private static float[] ensureMinSpan(float start, float end, float minSpan) {
            float span = end - start;
            if (span < minSpan) {
                float extra = (minSpan - span) * 0.5f;
                start -= extra;
                end += extra;
                if (start < 0f) {
                    end = Math.min(1f, end - start);
                    start = 0f;
                } else if (end > 1f) {
                    start = Math.max(0f, start - (end - 1f));
                    end = 1f;
                }
            }
            return new float[]{Math.max(0f, start), Math.min(1f, end)};
        }

        @Override
        public void close() {
            scoutPixels = null;
            rasterizer.close();
        }
    }
}
