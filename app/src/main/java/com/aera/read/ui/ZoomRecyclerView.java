package com.aera.read.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Continuous-scroll {@link RecyclerView} with unified viewport pinch-to-zoom and 2D panning
 * across all mounted pages (EPUB and PDF), so pinching in Scroll mode magnifies the entire
 * visible reading stream seamlessly instead of isolating a single page item.
 */
public class ZoomRecyclerView extends RecyclerView implements ZoomablePage {

    private static final float MAX_ZOOM = 4f;

    private float scale = 1f;
    private float tx;
    private float ty;
    private int reportedStep = 1;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector flingDetector;
    private final OverScroller flingScroller;
    private int lastFlingX;
    private int lastFlingY;

    private int panPointerId = -1;
    private float lastTouchX;
    private float lastTouchY;
    private boolean pinchedInGesture;
    private int consumedScrollY;

    private final Runnable flingTick = new Runnable() {
        @Override
        public void run() {
            if (!isZoomed() || !flingScroller.computeScrollOffset()) return;
            int cx = flingScroller.getCurrX();
            int cy = flingScroller.getCurrY();
            int dx = cx - lastFlingX;
            int dy = cy - lastFlingY;
            lastFlingX = cx;
            lastFlingY = cy;
            boolean moved = applyPan(dx, dy);
            if (moved && !flingScroller.isFinished()) {
                postOnAnimation(this);
            } else {
                flingScroller.forceFinished(true);
            }
        }
    };

    public ZoomRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        flingScroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyPinch(detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
        flingDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (!isZoomed() || scaleDetector.isInProgress()) return false;
                        flingScroller.forceFinished(true);
                        lastFlingX = 0;
                        lastFlingY = 0;
                        flingScroller.fling(0, 0,
                                Math.round(velocityX), Math.round(velocityY),
                                -60_000, 60_000, -240_000, 240_000);
                        postOnAnimation(flingTick);
                        return true;
                    }
                });
    }

    @Override
    public boolean isZoomed() {
        return scale > 1.01f;
    }

    public float zoomScale() {
        return scale;
    }

    /** Unscaled RecyclerView Y coordinate currently aligned with the top of the viewport. */
    public float zoomTopContentY() {
        return scale > 1.01f ? Math.max(0f, -ty / scale) : 0f;
    }

    @Override
    public void resetZoom() {
        flingScroller.forceFinished(true);
        if (scale == 1f && tx == 0f && ty == 0f) return;
        int scrollAdjust = scale > 1.01f ? Math.round(-ty / scale) : 0;
        scale = 1f;
        tx = 0f;
        ty = 0f;
        syncExternalZoom(true);
        if (scrollAdjust != 0) {
            scrollBy(0, scrollAdjust);
        }
        invalidate();
    }

    /** Clears zoom without scroll adjustment before an explicit page/chapter jump. */
    public void clearZoomForJump() {
        flingScroller.forceFinished(true);
        if (scale == 1f && tx == 0f && ty == 0f) return;
        scale = 1f;
        tx = 0f;
        ty = 0f;
        syncExternalZoom(true);
        invalidate();
    }

    @Override
    public void onScrolled(int dx, int dy) {
       super.onScrolled(dx, dy);
       consumedScrollY += dy;
    }

    private int scrollVerticallyConsumed(int dy) {
        if (dy == 0) return 0;
        consumedScrollY = 0;
        scrollBy(0, dy);
        return consumedScrollY;
    }

    void applyPinch(float factor, float focusX, float focusY) {
        float nextScale = Math.max(1f, Math.min(MAX_ZOOM, scale * factor));
        if (Math.abs(nextScale - scale) < 0.0008f) return;
        float ratio = nextScale / scale;
        float nextTx = focusX - (focusX - tx) * ratio;
        float nextTy = focusY - (focusY - ty) * ratio;
        scale = nextScale;
        if (scale <= 1.005f) {
            int scrollAdjust = Math.round(-nextTy);
            scale = 1f;
            tx = 0f;
            ty = 0f;
            if (scrollAdjust != 0) scrollVerticallyConsumed(scrollAdjust);
        } else {
            tx = clampX(nextTx);
            ty = settleVerticalOffset(nextTy);
        }
        syncExternalZoom(false);
        invalidate();
    }

    boolean applyPan(float dx, float dy) {
        if (!isZoomed()) return false;
        float beforeTx = tx;
        float beforeTy = ty;
        tx = clampX(tx + dx);
        float desiredTy = ty + dy;
        int scrolled = 0;
        float minTy = minTy();
        if (desiredTy > 0f) {
            int wantUp = -Math.round(desiredTy / scale);
            scrolled = scrollVerticallyConsumed(wantUp);
            desiredTy += scrolled * scale;
        } else if (desiredTy < minTy) {
            int wantDown = Math.round((minTy - desiredTy) / scale);
            scrolled = scrollVerticallyConsumed(wantDown);
            desiredTy += scrolled * scale;
        }
        ty = Math.max(minTy, Math.min(0f, desiredTy));
        boolean moved = Math.abs(tx - beforeTx) > 0.1f
                || Math.abs(ty - beforeTy) > 0.1f
                || scrolled != 0;
        if (moved) invalidate();
        return moved;
    }

    private float settleVerticalOffset(float desiredTy) {
        float minTy = minTy();
        if (desiredTy > 0f) {
            int consumed = scrollVerticallyConsumed(-Math.round(desiredTy / scale));
            desiredTy += consumed * scale;
        } else if (desiredTy < minTy) {
            int consumed = scrollVerticallyConsumed(Math.round((minTy - desiredTy) / scale));
            desiredTy += consumed * scale;
        }
        return Math.max(minTy, Math.min(0f, desiredTy));
    }

    private float clampX(float candidate) {
        float w = getWidth();
        return Math.max(w - w * scale, Math.min(0f, candidate));
    }

    private float minTy() {
        float h = getHeight();
        return h - h * scale;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            flingScroller.forceFinished(true);
            pinchedInGesture = false;
            panPointerId = e.getPointerId(0);
            lastTouchX = e.getX();
            lastTouchY = e.getY();
        }
        if (e.getPointerCount() > 1 || isZoomed()) {
            stopScroll();
            return true;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_UP && e.getPointerCount() == 2) {
            int gone = e.getActionIndex();
            int next = gone == 0 ? 1 : 0;
            panPointerId = e.getPointerId(next);
            lastTouchX = e.getX(next);
            lastTouchY = e.getY(next);
            return true;
        }

        if (e.getPointerCount() > 1) {
            if (!pinchedInGesture) {
                pinchedInGesture = true;
                flingScroller.forceFinished(true);
                stopScroll();
            }
            panPointerId = -1;
            return true;
        }

        if (isZoomed() || pinchedInGesture) {
            flingDetector.onTouchEvent(e);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    flingScroller.forceFinished(true);
                    panPointerId = e.getPointerId(0);
                    lastTouchX = e.getX();
                    lastTouchY = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isZoomed() && !scaleDetector.isInProgress()) {
                        if (panPointerId == -1) {
                            panPointerId = e.getPointerId(0);
                            lastTouchX = e.getX();
                            lastTouchY = e.getY();
                        } else {
                            int idx = e.findPointerIndex(panPointerId);
                            if (idx >= 0) {
                                float x = e.getX(idx);
                                float y = e.getY(idx);
                                applyPan(x - lastTouchX, y - lastTouchY);
                                lastTouchX = x;
                                lastTouchY = y;
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panPointerId = -1;
                    pinchedInGesture = false;
                    break;
                default:
                    break;
            }
            return true;
        }

        return super.onTouchEvent(e);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isZoomed()) {
            tx = clampX(tx);
            ty = Math.max(minTy(), Math.min(0f, ty));
        }
    }

    @Override
    public void onChildAttachedToWindow(@NonNull View child) {
        super.onChildAttachedToWindow(child);
        applyExternalZoomToChild(child);
    }

    private void syncExternalZoom(boolean force) {
        int step = Math.max(1, Math.min(4, (int) Math.ceil(scale - 0.02f)));
        if (!force && step == reportedStep) return;
        reportedStep = step;
        for (int i = 0; i < getChildCount(); i++) {
            applyExternalZoomToChild(getChildAt(i));
        }
    }

    private void applyExternalZoomToChild(View child) {
        float effective = isZoomed() ? scale : 1f;
        if (child instanceof PageView) {
            ((PageView) child).setExternalZoom(effective);
        } else if (child instanceof ZoomImageView) {
            ((ZoomImageView) child).setExternalZoom(effective);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        if (scale != 1f || tx != 0f || ty != 0f) {
            canvas.translate(tx, ty);
            canvas.scale(scale, scale);
        }
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
