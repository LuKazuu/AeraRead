package com.aera.read.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.util.Ui;

/**
 * PDF page view with hardware-accelerated full-page or background-only theming, crisp
 * viewport-filling layout, pinch zoom and smooth panning.
 */
public class ZoomImageView extends View implements ZoomablePage, ZoomController.Host {

    private static final float MAX_SCALE_FACTOR = 5f;
    private static final float PAGE_PAD_DP = 6f;
    public static final float DEFAULT_PAGE_RATIO = 1.414f;

    private Bitmap bitmap;
    private int bitmapPage = -1;
    private String bitmapTrim = ReaderPrefs.TRIM_NORMAL;
    private boolean maxTrim;
    private float expectedRatio = DEFAULT_PAGE_RATIO;
    private boolean relayoutPending;
    private ReaderTheme theme = ReaderTheme.LIGHT;
    private boolean fullPageTheme = true;

    private float scale = 1f;
    private float baseScale = 1f;
    private float externalZoom = 1f;
    private float tx;
    private float ty;
    private final boolean parentScrolls;
    private final ZoomController zoom;

    public interface ZoomListener {
        void onRenderScaleChanged(int level);
    }

    private ZoomListener zoomListener;
    private int reportedLevel = 1;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ZoomImageView(Context context) {
        this(context, false);
    }

    public ZoomImageView(Context context, android.util.AttributeSet attrs) {
        this(context, false);
    }

    public ZoomImageView(Context context, boolean parentScrolls) {
        super(context);
        setFocusable(false);
        this.parentScrolls = parentScrolls;
        this.zoom = new ZoomController(this, this, parentScrolls, true);
    }

    public void setTheme(ReaderTheme theme) {
        setTheme(theme, fullPageTheme);
    }

    public void setTheme(ReaderTheme theme, boolean fullPage) {
        this.theme = theme == null ? ReaderTheme.LIGHT : theme;
        this.fullPageTheme = fullPage;
        invalidate();
    }

    public boolean isFullPageTheme() {
        return fullPageTheme;
    }

    /** Deterministic scroll-mode card height shared by measurement and scroll-offset restoration. */
    public static int scrollItemHeightPx(Context context, int width, float pageRatio) {
        return scrollItemHeightPx(context, width, pageRatio, false);
    }

    public static int scrollItemHeightPx(Context context, int width, float pageRatio,
                                         boolean maxTrim) {
        if (width <= 0) return 1;
        float padH = Ui.dpf(context, maxTrim ? 0f : PAGE_PAD_DP);
        float padV = Ui.dpf(context, maxTrim ? 2f : PAGE_PAD_DP);
        float availW = Math.max(1f, width - 2f * padH);
        float ratio = pageRatio > 0.05f ? pageRatio : DEFAULT_PAGE_RATIO;
        return Math.max(1, Math.round(availW * ratio + 2f * padV));
    }

    public void preparePage(int page, float fallbackRatio) {
        preparePage(page, fallbackRatio, bitmapTrim);
    }

    /**
     * Prepares a recycled view for {@code page} and {@code trimMode}. Clears any bitmap/zoom from
     * a previously bound page or trim mode immediately so stale content never ghosts or overlaps.
     */
    public void preparePage(int page, float fallbackRatio, String trimMode) {
        String normTrim = ReaderPrefs.normalizeTrim(trimMode);
        boolean trimChanged = !normTrim.equals(bitmapTrim);
        bitmapTrim = normTrim;
        maxTrim = ReaderPrefs.TRIM_MAX.equals(normTrim);
        float nextRatio = fallbackRatio > 0.05f ? fallbackRatio : DEFAULT_PAGE_RATIO;
        boolean ratioChanged = Math.abs(nextRatio - expectedRatio) > 0.002f;
        expectedRatio = nextRatio;
        if (page != bitmapPage || trimChanged) {
            bitmap = null;
            bitmapPage = page;
            reportedLevel = 1;
            scale = 1f;
            baseScale = 1f;
            tx = 0f;
            ty = 0f;
            if (parentScrolls) {
                int wantH = getWidth() > 0
                        ? scrollItemHeightPx(getContext(), getWidth(), nextRatio, maxTrim) : 0;
                if (ratioChanged || trimChanged || getWidth() <= 0
                        || Math.abs(wantH - getHeight()) > 1) {
                    requestScrollRelayout();
                }
            }
            invalidate();
        }
    }

    public int boundPage() {
        return bitmapPage;
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        reportedLevel = 1;
        if (bmp != null && bmp.getWidth() > 0) {
            expectedRatio = bmp.getHeight() / (float) bmp.getWidth();
            if (parentScrolls) {
                int wantH = getWidth() > 0
                        ? scrollItemHeightPx(getContext(), getWidth(), expectedRatio, maxTrim) : 0;
                if (getWidth() <= 0 || Math.abs(wantH - getHeight()) > 2) {
                    requestScrollRelayout();
                }
            }
        }
        resetTransform();
        if (externalZoom > 1.02f) reportZoom();
        invalidate();
    }

    private void requestScrollRelayout() {
        ViewParent p = getParent();
        if (p instanceof RecyclerView) {
            RecyclerView rv = (RecyclerView) p;
            if (rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                if (!relayoutPending) {
                    relayoutPending = true;
                    rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrollStateChanged(@NonNull RecyclerView r, int state) {
                            if (state == RecyclerView.SCROLL_STATE_IDLE) {
                                r.removeOnScrollListener(this);
                                relayoutPending = false;
                                if (isAttachedToWindow()) requestLayout();
                            }
                        }
                    });
                }
                return;
            }
            if (rv.isComputingLayout()) {
                forceLayout();
                if (!relayoutPending) {
                    relayoutPending = true;
                    post(() -> {
                        relayoutPending = false;
                        if (isAttachedToWindow() && getWidth() > 0) {
                            int wantH = scrollItemHeightPx(
                                    getContext(), getWidth(), currentPageRatio(), maxTrim);
                            if (Math.abs(wantH - getHeight()) > 1) requestLayout();
                        }
                    });
                }
                return;
            }
        } else if (p == null) {
            forceLayout();
            return;
        }
        requestLayout();
    }

    private float currentPageRatio() {
        if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0) {
            return bitmap.getHeight() / (float) bitmap.getWidth();
        }
        return expectedRatio;
    }

    public void show(int page, Bitmap bmp) {
        if (bmp == null) return;
        if (page != bitmapPage || bitmap == null) {
            bitmapPage = page;
            setBitmap(bmp);
            return;
        }
        if (bmp.getWidth() <= bitmap.getWidth()) return;
        replaceBitmap(bmp);
    }

    public void replaceBitmap(Bitmap bmp) {
        if (bmp == null) return;
        if (bitmap == null || getWidth() == 0) {
            setBitmap(bmp);
            return;
        }
        float oldW = bitmap.getWidth() * scale;
        float oldH = bitmap.getHeight() * scale;
        float u = oldW <= 0 ? 0.5f : (getWidth() / 2f - tx) / oldW;
        float v = oldH <= 0 ? 0.5f : (getHeight() / 2f - ty) / oldH;
        bitmap = bmp;
        float padH = Ui.dpf(getContext(), maxTrim ? 0f : PAGE_PAD_DP);
        float padV = Ui.dpf(getContext(), maxTrim ? 2f : PAGE_PAD_DP);
        baseScale = Math.max(0.05f, Math.min((getWidth() - 2 * padH) / bmp.getWidth(),
                (getHeight() - 2 * padV) / bmp.getHeight()));
        scale = Math.max(baseScale, Math.min(baseScale * MAX_SCALE_FACTOR, oldW / bmp.getWidth()));
        tx = getWidth() / 2f - u * bmp.getWidth() * scale;
        ty = getHeight() / 2f - v * bmp.getHeight() * scale;
        clampTranslation();
        invalidate();
    }

    public void setZoomListener(ZoomListener listener) {
        zoomListener = listener;
        reportedLevel = 1;
        if (externalZoom > 1.02f) reportZoom();
    }

    public void setExternalZoom(float external) {
        float clamped = Math.max(1f, Math.min(4f, external));
        if (Math.abs(clamped - externalZoom) < 0.01f) return;
        externalZoom = clamped;
        reportZoom();
    }

    private void reportZoom() {
        if (zoomListener == null) return;
        float ratio = (scale / Math.max(0.001f, baseScale)) * externalZoom;
        int level = Math.max(1, Math.min(4, (int) Math.ceil(ratio - 0.02f)));
        if (level == reportedLevel) return;
        reportedLevel = level;
        zoomListener.onRenderScaleChanged(level);
    }

    public boolean hasBitmap() {
        return bitmap != null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int hSize = MeasureSpec.getSize(heightMeasureSpec);
        if (parentScrolls && hMode != MeasureSpec.EXACTLY && w > 0) {
            int desiredH = scrollItemHeightPx(getContext(), w, currentPageRatio(), maxTrim);
            if (hMode == MeasureSpec.AT_MOST) desiredH = Math.min(desiredH, hSize);
            setMeasuredDimension(w, desiredH);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetTransform();
    }

    private void resetTransform() {
        if (bitmap == null || getWidth() == 0) return;
        float padH = Ui.dpf(getContext(), maxTrim ? 0f : PAGE_PAD_DP);
        float padV = Ui.dpf(getContext(), maxTrim ? 2f : PAGE_PAD_DP);
        float sx = (getWidth() - 2 * padH) / bitmap.getWidth();
        float sy = (getHeight() - 2 * padV) / bitmap.getHeight();
        baseScale = Math.max(0.05f, Math.min(sx, sy));
        scale = baseScale;
        tx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        ty = (getHeight() - bitmap.getHeight() * scale) / 2f;
    }

    private void clampTranslation() {
        if (bitmap == null) return;
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        tx = w <= getWidth()
                ? (getWidth() - w) / 2f
                : Math.max(getWidth() - w, Math.min(0f, tx));
        ty = h <= getHeight()
                ? (getHeight() - h) / 2f
                : Math.max(getHeight() - h, Math.min(0f, ty));
    }

    @Override
    public void onPinchScale(float factor, float focusX, float focusY) {
        if (bitmap == null) return;
        float clamped = Math.max(baseScale,
                Math.min(baseScale * MAX_SCALE_FACTOR, scale * factor));
        float ratio = clamped / scale;
        tx = focusX - (focusX - tx) * ratio;
        ty = focusY - (focusY - ty) * ratio;
        scale = clamped;
        clampTranslation();
        invalidate();
        reportZoom();
    }

    @Override
    public void onPan(float dx, float dy) {
        if (bitmap == null) return;
        tx += dx;
        ty += dy;
        clampTranslation();
        invalidate();
    }

    @Override
    public void resetZoom() {
        if (!isZoomed()) return;
        resetTransform();
        invalidate();
    }

    @Override
    public boolean isZoomed() {
        return scale > baseScale * 1.02f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        canvas.save();
        canvas.clipRect(0, 0, viewW, viewH);
        canvas.drawColor(theme.bg);
        if (bitmap == null || bitmap.isRecycled()) {
            float padH = Ui.dpf(getContext(), maxTrim ? 1f : PAGE_PAD_DP);
            float padV = Ui.dpf(getContext(), maxTrim ? 2f : PAGE_PAD_DP);
            placeholderPaint.setColor(Ui.alpha(theme.fg, 0.05f));
            rect.set(padH, padV, viewW - padH, viewH - padV);
            float radius = Ui.dp(getContext(), 6);
            canvas.drawRoundRect(rect, radius, radius, placeholderPaint);
        } else {
            bitmapPaint.setColorFilter(theme.pdfColorFilter(fullPageTheme));
            canvas.save();
            canvas.translate(tx, ty);
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
            canvas.restore();
        }
        if (parentScrolls) {
            placeholderPaint.setColor(Ui.alpha(theme.fg, 0.10f));
            float sep = Math.max(1f, Ui.dpf(getContext(), 1f));
            canvas.drawRect(0f, viewH - sep, viewW, viewH, placeholderPaint);
        }
        canvas.restore();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() instanceof ZoomRecyclerView) return false;
        if (bitmap == null) return false;
        return zoom.onTouchEvent(event);
    }

    /** {scale, tx, ty} as drawn, exposed so tests can assert a stable pinch. */
    float[] transform() {
        return new float[]{scale, tx, ty};
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
