package com.aera.read.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.aera.read.data.ReaderTheme;
import com.aera.read.util.Ui;

/** Circular preview of a reader color theme. */
public class ThemeSwatch extends View {

    private ReaderTheme theme = ReaderTheme.PAPER;
    private boolean selected;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ThemeSwatch(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(context, 2));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dpf(context, 1));
        letters.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letters.setTextAlign(Paint.Align.CENTER);
    }

    public void setTheme(ReaderTheme theme) {
        this.theme = theme;
        invalidate();
    }

    public void setSelectedState(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float ringRadius = Math.min(getWidth(), getHeight()) / 2f - Ui.dpf(getContext(), 1);
        float circleRadius = ringRadius - Ui.dpf(getContext(), 5);

        if (selected) {
            boolean nightUi = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            ring.setColor(nightUi ? Color.WHITE
                    : Ui.colorAttr(getContext(), com.google.android.material.R.attr.colorOnSurface));
            canvas.drawCircle(cx, cy, ringRadius, ring);
        }
        fill.setColor(theme.bg);
        canvas.drawCircle(cx, cy, circleRadius, fill);
        border.setColor(Ui.alpha(theme.fg, 0.28f));
        canvas.drawCircle(cx, cy, circleRadius, border);

        letters.setColor(theme.fg);
        letters.setTextSize(Ui.dpf(getContext(), 14));
        Paint.FontMetrics fm = letters.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("Aa", cx, baseline, letters);
    }
}
