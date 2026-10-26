package com.aera.read.epub;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

import com.aera.read.data.ReaderTheme;

/**
 * Flows an inline image, icon, mathematical formula or East Asian gaiji glyph directly inside
 * a paragraph line without breaking paragraph flow and without decoding on the UI draw thread.
 */
public final class InlineImageSpan extends ReplacementSpan {

    /** Active draw host set by {@code PageView} on the UI draw thread. */
    public static final class DrawHost {
        public ImageStore images;
        public Runnable onImageDecoded;
        public ReaderTheme theme = ReaderTheme.LIGHT;
        public float zoomScale = 1f;
    }

    private static final ThreadLocal<DrawHost> ACTIVE_HOST =
            ThreadLocal.withInitial(DrawHost::new);

    public static void bindHost(ImageStore images, Runnable onDecoded, ReaderTheme theme,
                                float zoomScale) {
        DrawHost h = ACTIVE_HOST.get();
        h.images = images;
        h.onImageDecoded = onDecoded;
        h.theme = theme == null ? ReaderTheme.LIGHT : theme;
        h.zoomScale = Math.max(1f, zoomScale);
    }

    public static void clearHost() {
        DrawHost h = ACTIVE_HOST.get();
        h.images = null;
        h.onImageDecoded = null;
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private int cachedTintColor;
    private ColorFilter cachedTintFilter;

    public final String path;
    public final int drawWidth;
    public final int drawHeight;
    private final int ascentPx;
    private final int descentPx;

    public InlineImageSpan(EpubBook.InlineImage img, float textSizePx, int baseAscent,
                           int baseDescent, int maxWidth) {
        this.path = img.path;
        int availW = Math.max(16, maxWidth);

        float targetW;
        float targetH;
        float aspect = (img.imgW > 0 && img.imgH > 0)
                ? (img.imgW / (float) img.imgH) : 1f;

        if (img.emW > 0f && img.emH > 0f) {
            targetW = img.emW * textSizePx;
            targetH = img.emH * textSizePx;
        } else if (img.emH > 0f) {
            targetH = img.emH * textSizePx;
            targetW = targetH * aspect;
        } else if (img.emW > 0f) {
            targetW = img.emW * textSizePx;
            targetH = targetW / aspect;
        } else if (img.pxW > 0f && img.pxH > 0f) {
            float scale = textSizePx / 16f;
            targetW = img.pxW * scale;
            targetH = img.pxH * scale;
        } else if (img.pxH > 0f) {
            targetH = img.pxH * (textSizePx / 16f);
            targetW = targetH * aspect;
        } else if (img.pxW > 0f) {
            targetW = img.pxW * (textSizePx / 16f);
            targetH = targetW / aspect;
        } else if (img.imgW <= 64 && img.imgH <= 64) {
            // Classic gaiji / inline icon: match ~1.12em cap+ascender optical box.
            targetH = Math.max(textSizePx * 1.05f,
                    Math.min(img.imgH * (textSizePx / 20f), textSizePx * 1.45f));
            targetW = targetH * aspect;
        } else if (aspect >= 0.72f && aspect <= 1.4f && img.imgW <= 256) {
            // High-DPI square gaiji / symbol / badge.
            targetH = textSizePx * 1.15f;
            targetW = targetH * aspect;
        } else if (aspect > 1.4f) {
            // Inline formula / wide notation / ornament inside sentence.
            targetH = Math.min(img.imgH, textSizePx * 1.65f);
            targetW = targetH * aspect;
        } else {
            // Taller inline figure inside text paragraph.
            targetH = Math.min(img.imgH, textSizePx * 4.0f);
            targetW = targetH * aspect;
        }

        if (targetW > availW) {
            float shrink = availW / targetW;
            targetW = availW;
            targetH *= shrink;
        }
        this.drawWidth = Math.max(1, Math.round(targetW));
        this.drawHeight = Math.max(1, Math.round(targetH));

        // Align optical center of the inline image with the text line's x-height/cap center.
        int glyphCenter = (baseAscent + baseDescent) / 2;
        int top = glyphCenter - (drawHeight / 2);
        int bottom = top + drawHeight;
        this.ascentPx = Math.min(baseAscent, top);
        this.descentPx = Math.max(baseDescent, bottom);
    }

    public int requiredAscent() {
        return ascentPx;
    }

    public int requiredDescent() {
        return descentPx;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        if (fm != null) {
            fm.ascent = Math.min(fm.ascent, ascentPx);
            fm.descent = Math.max(fm.descent, descentPx);
            fm.top = Math.min(fm.top, fm.ascent);
            fm.bottom = Math.max(fm.bottom, fm.descent);
        }
        return drawWidth;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        DrawHost host = ACTIVE_HOST.get();
        ImageStore store = host.images;
        if (store == null) return;

        int reqW = Math.max(drawWidth, Math.round(drawWidth * host.zoomScale));
        Bitmap bmp = store.request(path, reqW, host.onImageDecoded);
        if (bmp == null || bmp.isRecycled()) return;

        int imgTop = y + ((ascentPx + descentPx - drawHeight) / 2);
        dst.set(x, imgTop, x + drawWidth, imgTop + drawHeight);

        // Light theme applies zero effects; in Sepia/Slate/Night, monochrome transparent
        // gaiji/equation glyphs tint cleanly to theme.fg while colored icons stay full-color.
        ReaderTheme theme = host.theme;
        if (theme != ReaderTheme.LIGHT && store.isMonochromeInk(path)) {
            if (cachedTintFilter == null || cachedTintColor != theme.fg) {
                cachedTintColor = theme.fg;
                cachedTintFilter = new PorterDuffColorFilter(theme.fg, PorterDuff.Mode.SRC_IN);
            }
            bitmapPaint.setColorFilter(cachedTintFilter);
        } else {
            bitmapPaint.setColorFilter(null);
        }
        canvas.drawBitmap(bmp, null, dst, bitmapPaint);
    }
}
