package com.aera.read.ui;

import android.content.Context;
import android.util.AttributeSet;

import com.google.android.material.imageview.ShapeableImageView;

/**
 * 3:4 book-cover {@link ShapeableImageView}. Replaces {@code ConstraintLayout}'s
 * {@code layout_constraintDimensionRatio="3:4"} with a single-pass measurement so the entire
 * {@code androidx.constraintlayout} library is eliminated from the APK.
 */
public final class CoverImageView extends ShapeableImageView {

    public CoverImageView(Context context) {
        super(context);
    }

    public CoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CoverImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 4f / 3f);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
