package com.aera.read.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * Toolbar that puts its title on the icon line. AppCompat places a toolbar without a
 * subtitle a few pixels above the action icons; the correction is applied inside
 * {@link #onLayout} so the title is never drawn once at the wrong height and then moved.
 */
public class ReaderToolbar extends MaterialToolbar {

    public ReaderToolbar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        TextView title = null;
        float iconCentre = Float.NaN;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() == 0) continue;
            if (child instanceof TextView) {
                if (title == null) title = (TextView) child;
                continue;
            }
            if (Float.isNaN(iconCentre)) iconCentre = child.getTop() + child.getHeight() / 2f;
        }
        if (title == null || Float.isNaN(iconCentre)) return;
        float centre = title.getTop() + title.getHeight() / 2f;
        title.setTranslationY(iconCentre - centre);
    }
}
