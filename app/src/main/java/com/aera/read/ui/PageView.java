package com.aera.read.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.ImageStore;
import com.aera.read.epub.InlineImageSpan;
import com.aera.read.epub.PagedBook;
import com.aera.read.util.Ui;

/** Draws one paginated EPUB page: rich inline text, inline images/gaiji, illustrations, zoom. */
public class PageView extends View implements ZoomablePage, ZoomController.Host {

    private static final float MAX_ZOOM = 4f;
    private static final int MAX_IMAGE_WIDTH = 2048;

    private PagedBook book;
    private PagedBook.Page page;
    private float scale = 1f;
    private float externalZoom = 1f;
    private float tx;
    private float ty;
    private final ZoomController zoom;
    private ReaderTheme theme = ReaderTheme.LIGHT;
    private ImageStore images;

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final Runnable invalidateSelf = this::invalidate;

    public PageView(Context context) {
        this(context, false);
    }

    public PageView(Context context, boolean parentScrolls) {
        super(context);
        zoom = new ZoomController(this, this, parentScrolls, false);
    }

    @Override
    public boolean isZoomed() {
        return scale > 1.01f;
    }

    @Override
    public void onPinchScale(float factor, float focusX, float focusY) {
        float clamped = Math.max(1f, Math.min(MAX_ZOOM, scale * factor));
        if (Math.abs(clamped - scale) < 0.001f) return;
        tx = focusX - (focusX - tx) * (clamped / scale);
        ty = focusY - (focusY - ty) * (clamped / scale);
        scale = clamped;
        clampTranslation();
        invalidate();
    }

    @Override
    public void onPan(float dx, float dy) {
        tx += dx;
        ty += dy;
        clampTranslation();
        invalidate();
    }

    private void clampTranslation() {
        float w = getWidth();
        float h = getHeight();
        tx = Math.max(w - w * scale, Math.min(0f, tx));
        ty = Math.max(h - h * scale, Math.min(0f, ty));
    }

    @Override
    public void resetZoom() {
        if (scale == 1f && tx == 0f && ty == 0f) return;
        scale = 1f;
        tx = 0f;
        ty = 0f;
        invalidate();
    }

    public void setExternalZoom(float external) {
        float clamped = Math.max(1f, Math.min(MAX_ZOOM, external));
        if (Math.abs(clamped - externalZoom) < 0.01f) return;
        externalZoom = clamped;
        preloadImages();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() instanceof ZoomRecyclerView) return false;
        return zoom.onTouchEvent(event);
    }

    /** {scale, tx, ty} of the page, exposed so tests can assert a stable pinch. */
    float[] transform() {
        return new float[]{scale, tx, ty};
    }

    public void setData(PagedBook book, PagedBook.Page page, ReaderTheme theme,
                        ImageStore images) {
        if (this.page != page) resetZoom();
        this.book = book;
        this.page = page;
        this.theme = theme == null ? ReaderTheme.LIGHT : theme;
        this.images = images;
        invalidate();
        post(this::preloadImages);
    }

    /** Warms block and inline illustrations off the drawing thread so the first frame has them. */
    private void preloadImages() {
        if (images == null || book == null || page == null || getWidth() == 0) return;
        float contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        int step = Math.max(1, (int) Math.ceil(Math.min(MAX_ZOOM, scale * externalZoom) - 0.01f));
        for (PagedBook.Segment seg : page.segments) {
            if (seg.isImage()) {
                images.request(seg.block.imagePath, bitmapWidthFor(seg, contentW), invalidateSelf);
            } else if (seg.block != null && seg.block.inlineImages != null) {
                for (EpubBook.InlineImage img : seg.block.inlineImages) {
                    int reqW = Math.min(Math.max(48, img.imgW) * step, MAX_IMAGE_WIDTH);
                    images.request(img.path, reqW, invalidateSelf);
                }
            }
        }
    }

    public void setTheme(ReaderTheme theme) {
        this.theme = theme == null ? ReaderTheme.LIGHT : theme;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.drawColor(theme.bg);
        if (book == null || page == null) {
            canvas.restore();
            return;
        }

        int padL = getPaddingLeft();
        int padT = getPaddingTop();
        float contentW = getWidth() - padL - getPaddingRight();

        if (scale != 1f || tx != 0f || ty != 0f) {
            canvas.translate(tx, ty);
            canvas.scale(scale, scale);
        }
        canvas.translate(padL, padT);

        book.styles.setColor(theme.fg);
        float effectiveScale = Math.min(MAX_ZOOM, scale * externalZoom);
        InlineImageSpan.bindHost(images, invalidateSelf, theme, effectiveScale);
        try {
            for (PagedBook.Segment seg : page.segments) {
                if (seg.isImage()) drawImage(canvas, seg, contentW);
                else drawText(canvas, seg, contentW);
            }
        } finally {
            InlineImageSpan.clearHost();
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

    int bitmapWidthFor(PagedBook.Segment seg, float contentW) {
        int drawW = layoutWidth(seg, contentW);
        float effectiveScale = Math.min(MAX_ZOOM, scale * externalZoom);
        int step = Math.max(1, (int) Math.ceil(effectiveScale - 0.01f));
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
            final int width = bitmapWidthFor(seg, contentW);
            bitmap = images.request(block.imagePath, width, invalidateSelf);
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
