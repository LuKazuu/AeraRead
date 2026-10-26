package com.aera.read.ui;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;

/**
 * Shared two-finger pinch + single-finger pan gesture state machine used by both
 * {@link PageView} and {@link ZoomImageView}, keeping parent ViewPager2 / RecyclerView
 * touch interception and multi-pointer lift handoffs completely unified.
 */
final class ZoomController {

    interface Host {
        boolean isZoomed();

        void onPinchScale(float factor, float focusX, float focusY);

        void onPan(float dx, float dy);
    }

    private final View view;
    private final Host host;
    private final boolean parentScrolls;
    private final boolean callPerformClick;
    private final ScaleGestureDetector scaleDetector;
    private int panPointer = -1;
    private float lastX;
    private float lastY;

    ZoomController(View view, Host host, boolean parentScrolls, boolean callPerformClick) {
        this.view = view;
        this.host = host;
        this.parentScrolls = parentScrolls;
        this.callPerformClick = callPerformClick;
        this.scaleDetector = new ScaleGestureDetector(view.getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        host.onPinchScale(detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
    }

    boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panPointer = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                if (host.isZoomed()) disallowParentIntercept(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                disallowParentIntercept(true);
                panPointer = -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                    disallowParentIntercept(true);
                    panPointer = -1;
                } else if (host.isZoomed()) {
                    int index = panPointer < 0 ? -1 : event.findPointerIndex(panPointer);
                    if (index >= 0) {
                        float x = event.getX(index);
                        float y = event.getY(index);
                        host.onPan(x - lastX, y - lastY);
                        lastX = x;
                        lastY = y;
                        disallowParentIntercept(true);
                    }
                } else if (!parentScrolls) {
                    disallowParentIntercept(false);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int gone = event.getActionIndex();
                int next = gone == 0 ? 1 : 0;
                if (next < event.getPointerCount()) {
                    panPointer = event.getPointerId(next);
                    lastX = event.getX(next);
                    lastY = event.getY(next);
                }
                break;
            case MotionEvent.ACTION_UP:
                panPointer = -1;
                disallowParentIntercept(false);
                if (callPerformClick) view.performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                panPointer = -1;
                disallowParentIntercept(false);
                break;
            default:
                break;
        }
        return true;
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = view.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
