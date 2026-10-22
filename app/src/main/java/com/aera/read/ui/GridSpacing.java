package com.aera.read.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Even gutters for a grid, with identical outer margins. */
public class GridSpacing extends RecyclerView.ItemDecoration {

    private final int spacing;
    private final int spanCount;

    public GridSpacing(int spacingPx, int spanCount) {
        this.spacing = spacingPx;
        this.spanCount = Math.max(1, spanCount);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;
        int column = position % spanCount;
        outRect.left = column * spacing / spanCount;
        outRect.right = spacing - (column + 1) * spacing / spanCount;
        outRect.top = position < spanCount ? 0 : spacing;
        outRect.bottom = 0;
    }
}
