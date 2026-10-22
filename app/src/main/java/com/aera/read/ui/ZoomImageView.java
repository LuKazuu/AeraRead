package com.aera.read.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;

import com.aera.read.data.ReaderTheme;
import com.aera.read.util.Ui;

/** PDF page view with pinch zoom and panning. Zoom is disabled in scroll mode. */
public class ZoomImageView extends View {

    private Bitmap bitmap;
    /** The page the bitmap on screen belongs to, so a late render cannot be mistaken. */
    private int bitmapPage = -1;
    private ReaderTheme theme = ReaderTheme.PAPER;
    /** True in scroll mode: one finger scrolls the list, two fingers zoom the page. */
    private final boolean parentScrolls;
    private int panPointer = -1;

    private float scale = 1f;
    private float baseScale = 1f;
    private float tx;
    private float ty;
    private final float maxScaleFactor = 5f;

    /** How many times the fitted page is magnified, rounded up; 1 means no zoom. */
    public interface ZoomListener {
        void onRenderScaleChanged(int level);
    }

    private ZoomListener zoomListener;
    private int reportedLevel = 1;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ScaleGestureDetector scaleDetector;
    private float lastX;
    private float lastY;

    public ZoomImageView(Context context) {
        this(context, false);
    }

    public ZoomImageView(Context context, android.util.AttributeSet attrs) {
        this(context, false);
    }

    /** @param parentScrolls true in scroll mode: one finger scrolls, two fingers zoom */
    public ZoomImageView(Context context, boolean parentScrolls) {
        super(context);
        this.parentScrolls = parentScrolls;
        setFocusable(false);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyScale(scale * detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
    }

    public void setTheme(ReaderTheme theme) {
        this.theme = theme;
        invalidate();
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        reportedLevel = 1;
        resetTransform();
        invalidate();
    }

    /**
     * Shows a bitmap that belongs to {@code page}. A different page starts fitted to the
     * frame; a sharper render of the page already on screen keeps the reader's zoom and
     * place, and a weaker render of it is ignored.
     */
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

    /**
     * Swaps in a sharper bitmap without moving the page: the visible rectangle stays put,
     * so a magnified PDF keeps its place when the high-resolution render arrives.
     */
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
        float pad = Ui.dpf(getContext(), 10);
        baseScale = Math.max(0.05f, Math.min((getWidth() - 2 * pad) / bmp.getWidth(),
                (getHeight() - 2 * pad) / bmp.getHeight()));
        scale = Math.max(baseScale, Math.min(baseScale * maxScaleFactor, oldW / bmp.getWidth()));
        tx = getWidth() / 2f - u * bmp.getWidth() * scale;
        ty = getHeight() / 2f - v * bmp.getHeight() * scale;
        clampTranslation();
        invalidate();
    }

    public void setZoomListener(ZoomListener listener) {
        zoomListener = listener;
        reportedLevel = 1;
    }

    /** @param level magnification rounded up, so pinching out asks for a sharper page */
    private void reportZoom() {
        if (zoomListener == null) return;
        int level = Math.max(1, Math.min(4, (int) Math.ceil(scale / baseScale - 0.02f)));
        if (level == reportedLevel) return;
        reportedLevel = level;
        zoomListener.onRenderScaleChanged(level);
    }

    public boolean hasBitmap() {
        return bitmap != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetTransform();
    }

    private void resetTransform() {
        if (bitmap == null || getWidth() == 0) return;
        float pad = Ui.dpf(getContext(), 10);
        float sx = (getWidth() - 2 * pad) / bitmap.getWidth();
        float sy = (getHeight() - 2 * pad) / bitmap.getHeight();
        baseScale = Math.max(0.05f, Math.min(sx, sy));
        scale = baseScale;
        tx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        ty = (getHeight() - bitmap.getHeight() * scale) / 2f;
    }

    private void clampTranslation() {
        if (bitmap == null) return;
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        if (w <= getWidth()) {
            tx = (getWidth() - w) / 2f;
        } else {
            tx = Math.max(getWidth() - w, Math.min(0f, tx));
        }
        if (h <= getHeight()) {
            ty = (getHeight() - h) / 2f;
        } else {
            ty = Math.max(getHeight() - h, Math.min(0f, ty));
        }
    }

    private void applyScale(float newScale, float focusX, float focusY) {
        if (bitmap == null) return;
        float clamped = Math.max(baseScale, Math.min(baseScale * maxScaleFactor, newScale));
        float factor = clamped / scale;
        tx = focusX - (focusX - tx) * factor;
        ty = focusY - (focusY - ty) * factor;
        scale = clamped;
        clampTranslation();
        invalidate();
        reportZoom();
    }

    /** Puts a magnified page back to fit. */
    void resetZoom() {
        if (!isZoomed()) return;
        resetTransform();
        invalidate();
    }

    boolean isZoomed() {
        return scale > baseScale * 1.02f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(theme.bg);
        if (bitmap == null) {
            float pad = Ui.dpf(getContext(), 10);
            placeholderPaint.setColor(Ui.alpha(theme.fg, 0.05f));
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float radius = Ui.dp(getContext(), 6);
            canvas.drawRoundRect(rect, radius, radius, placeholderPaint);
            return;
        }
        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
        if (theme.dark) {
            // soften the paper-white page in night themes
            dimPaint.setColor(0xFF000000);
            dimPaint.setAlpha(56);
            canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), dimPaint);
        }
        canvas.restore();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panPointer = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                // a magnified page pans under one finger; the list must not claim the drag
                if (isZoomed()) requestParentIntercept(false);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                requestParentIntercept(false);
                panPointer = -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                    requestParentIntercept(false);
                    panPointer = -1;
                } else if (isZoomed()) {
                    int index = panPointer < 0 ? -1 : event.findPointerIndex(panPointer);
                    if (index >= 0) {
                        float x = event.getX(index);
                        float y = event.getY(index);
                        tx += x - lastX;
                        ty += y - lastY;
                        lastX = x;
                        lastY = y;
                        clampTranslation();
                        invalidate();
                        requestParentIntercept(false);
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // follow whichever finger is left, not the old index
                int gone = event.getActionIndex();
                int next = gone == 0 ? 1 : 0;
                if (next < event.getPointerCount()) {
                    panPointer = event.getPointerId(next);
                    lastX = event.getX(next);
                    lastY = event.getY(next);
                }
                break;
            case MotionEvent.ACTION_UP:
                requestParentIntercept(true);
                panPointer = -1;
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                requestParentIntercept(true);
                panPointer = -1;
                break;
            default:
                break;
        }
        return true;
    }

    /** {scale, tx, ty} as drawn, exposed so tests can assert a stable pinch. */
    float[] transform() {
        return new float[]{scale, tx, ty};
    }

    private void requestParentIntercept(boolean allow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(!allow);
    }
}
