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
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.ImageStore;
import com.aera.read.epub.PagedBook;
import com.aera.read.util.Ui;

/** Draws one paginated EPUB page: text, illustrations, pinch zoom and panning. */
public class PageView extends View {

    private static final float MAX_ZOOM = 4f;
    /** Ceiling for a magnified bitmap; beyond this the source detail stops paying off. */
    private static final int MAX_IMAGE_WIDTH = 2048;

    private PagedBook book;
    private PagedBook.Page page;
    /** True in scroll mode, where a one-finger drag belongs to the list, not the page. */
    private final boolean parentScrolls;
    private float scale = 1f;
    private float tx;
    private float ty;
    private final ScaleGestureDetector scaleDetector;
    private float lastX;
    private float lastY;
    /** The finger being followed for panning; -1 while the pinch owns the gesture. */
    private int panPointer = -1;
    private ReaderTheme theme = ReaderTheme.PAPER;
    private ImageStore images;

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    public PageView(Context context) {
        this(context, false);
    }

    /** @param parentScrolls true in scroll mode: one finger scrolls, two fingers zoom */
    public PageView(Context context, boolean parentScrolls) {
        super(context);
        this.parentScrolls = parentScrolls;
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

    /** True while the page is magnified; the reader uses this to leave taps alone. */
    public boolean isZoomed() {
        return scale > 1.01f;
    }

    private void applyScale(float next, float focusX, float focusY) {
        float clamped = Math.max(1f, Math.min(MAX_ZOOM, next));
        if (Math.abs(clamped - scale) < 0.001f) return;
        // keep the pinched point under the fingers
        tx = focusX - (focusX - tx) * (clamped / scale);
        ty = focusY - (focusY - ty) * (clamped / scale);
        scale = clamped;
        clampTranslation();
        invalidate();
    }

    private void clampTranslation() {
        float w = getWidth();
        float h = getHeight();
        float minTx = w - w * scale;
        float minTy = h - h * scale;
        tx = Math.max(minTx, Math.min(0f, tx));
        ty = Math.max(minTy, Math.min(0f, ty));
    }

    public void resetZoom() {
        if (scale == 1f && tx == 0f && ty == 0f) return;
        scale = 1f;
        tx = 0f;
        ty = 0f;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panPointer = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                // a magnified page pans under one finger; the pager must not claim the
                // drag first and snap to a neighbouring page when the finger lifts
                if (isZoomed()) disallowParentIntercept(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // two fingers: the page keeps the gesture, the pager must not turn
                disallowParentIntercept(true);
                panPointer = -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                    disallowParentIntercept(true);
                    panPointer = -1;
                } else if (isZoomed()) {
                    int index = pointerIndex(event, panPointer);
                    if (index >= 0) {
                        float x = event.getX(index);
                        float y = event.getY(index);
                        tx += x - lastX;
                        ty += y - lastY;
                        lastX = x;
                        lastY = y;
                        clampTranslation();
                        invalidate();
                        disallowParentIntercept(true);
                    }
                } else if (!parentScrolls) {
                    // a one-finger drag on an unzoomed page is left to the pager
                    disallowParentIntercept(false);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // follow a finger that is still down: tracking index 0 after the first
                // finger lifts would jump the page by the gap between the two fingers
                int gone = event.getActionIndex();
                int next = gone == 0 ? 1 : 0;
                if (next < event.getPointerCount()) {
                    panPointer = event.getPointerId(next);
                    lastX = event.getX(next);
                    lastY = event.getY(next);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panPointer = -1;
                // the page is back under the pager's control once the fingers are gone
                disallowParentIntercept(false);
                break;
            default:
                break;
        }
        return true;
    }

    private static int pointerIndex(MotionEvent event, int pointerId) {
        if (pointerId < 0) return -1;
        return event.findPointerIndex(pointerId);
    }

    /** {scale, tx, ty} of the page, exposed so tests can assert a stable pinch. */
    float[] transform() {
        return new float[]{scale, tx, ty};
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    public void setData(PagedBook book, PagedBook.Page page, ReaderTheme theme,
                        ImageStore images) {
        if (this.page != page) resetZoom();
        this.book = book;
        this.page = page;
        this.theme = theme;
        this.images = images;
        invalidate();
        post(this::preloadImages);
    }

    /** Warms the page's illustrations off the drawing thread, so the first frame has them. */
    private void preloadImages() {
        if (images == null || book == null || page == null || getWidth() == 0) return;
        float contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        for (PagedBook.Segment seg : page.segments) {
            if (seg.isImage()) {
                images.request(seg.block.imagePath, bitmapWidthFor(seg, contentW),
                        this::invalidate);
            }
        }
    }

    public void setTheme(ReaderTheme theme) {
        this.theme = theme;
        invalidate();
    }

    /** Taps are handled by the reader; this keeps accessibility services happy. */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(theme.bg);
        if (book == null || page == null) return;

        int padL = getPaddingLeft();
        int padT = getPaddingTop();
        float contentW = getWidth() - padL - getPaddingRight();

        canvas.save();
        if (scale != 1f || tx != 0f || ty != 0f) {
            canvas.translate(tx, ty);
            canvas.scale(scale, scale);
        }
        canvas.translate(padL, padT);

        book.styles.setColor(theme.fg);
        for (PagedBook.Segment seg : page.segments) {
            if (seg.isImage()) drawImage(canvas, seg, contentW);
            else drawText(canvas, seg, contentW);
        }
        canvas.restore();
    }

    private void drawText(Canvas canvas, PagedBook.Segment seg, float contentW) {
        canvas.save();
        canvas.clipRect(0, seg.y, contentW, seg.y + seg.height);
        float indent = book.styles.indentFor(seg.block.style);
        canvas.translate(indent, seg.y - seg.layout.getLineTop(seg.startLine));
        seg.layout.draw(canvas);
        canvas.restore();
    }

    /**
     * Width of the bitmap worth having for this illustration. Zooming magnifies the canvas,
     * so the source grows with it; whole zoom steps keep it to at most one decode per step
     * instead of a new one at every frame of a pinch.
     */
    int bitmapWidthFor(PagedBook.Segment seg, float contentW) {
        int drawW = layoutWidth(seg, contentW);
        int step = Math.max(1, (int) Math.ceil(scale - 0.01f));
        return Math.min(drawW * step, MAX_IMAGE_WIDTH);
    }

    private int layoutWidth(PagedBook.Segment seg, float contentW) {
        EpubBook.Block block = seg.block;
        int drawW = block.imgH > 0
                ? Math.round(seg.height * (float) block.imgW / block.imgH)
                : Math.round(contentW);
        return Math.min(drawW, Math.round(contentW));
    }

    private void drawImage(Canvas canvas, PagedBook.Segment seg, float contentW) {
        EpubBook.Block block = seg.block;
        int drawH = seg.height;
        int drawW = layoutWidth(seg, contentW);
        float left = (contentW - drawW) / 2f;
        Bitmap bitmap = null;
        if (images != null) {
            // draw what is in memory and ask for the sharper copy in the background: a
            // decode on this thread would stall every frame of the pinch
            final int width = bitmapWidthFor(seg, contentW);
            bitmap = images.request(block.imagePath, width, this::invalidate);
        }
        if (bitmap != null) {
            tmpRect.set(left, seg.y, left + drawW, seg.y + drawH);
            canvas.drawBitmap(bitmap, null, tmpRect, imagePaint);
        } else {
            placeholderPaint.setColor(Ui.alpha(theme.fg, 0.06f));
            tmpRect.set(left, seg.y, left + drawW, seg.y + drawH);
            float radius = Ui.dp(getContext(), 6);
            canvas.drawRoundRect(tmpRect, radius, radius, placeholderPaint);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
