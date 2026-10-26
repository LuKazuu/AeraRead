package com.aera.read.epub;

import android.graphics.Paint;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/**
 * Zero-allocation-per-draw inline formatting span applying bold, italic, underline,
 * strikethrough, inline code/monospace, superscript, subscript, and small/small-caps
 * directly onto Android's {@link TextPaint} work state.
 */
public final class InlineStyleSpan extends MetricAffectingSpan {

    private static final float CODE_SCALE = 0.90f;
    private static final float SMALL_SCALE = 0.84f;
    private static final float SCRIPT_SCALE = 0.72f;
    private static final float SUP_SHIFT = 0.34f;
    private static final float SUB_SHIFT = 0.18f;

    private final TextStyles styles;
    private final int blockStyle;
    public final int mask;

    public InlineStyleSpan(TextStyles styles, int blockStyle, int mask) {
        this.styles = styles;
        this.blockStyle = blockStyle;
        this.mask = mask;
    }

    public boolean hasVerticalShift() {
        return (mask & (EpubBook.InlineSpan.SUP | EpubBook.InlineSpan.SUB)) != 0;
    }

    public int requiredAscent(TextPaint basePaint) {
        Paint.FontMetricsInt fm = basePaint.getFontMetricsInt();
        if ((mask & EpubBook.InlineSpan.SUP) != 0) {
            int shift = Math.round(basePaint.getTextSize() * SUP_SHIFT);
            return Math.round(fm.ascent * SCRIPT_SCALE) - shift;
        }
        return fm.ascent;
    }

    public int requiredDescent(TextPaint basePaint) {
        Paint.FontMetricsInt fm = basePaint.getFontMetricsInt();
        if ((mask & EpubBook.InlineSpan.SUB) != 0) {
            int shift = Math.round(basePaint.getTextSize() * SUB_SHIFT);
            return Math.round(fm.descent * SCRIPT_SCALE) + shift;
        }
        return fm.descent;
    }

    @Override
    public void updateMeasureState(TextPaint tp) {
        apply(tp);
    }

    @Override
    public void updateDrawState(TextPaint tp) {
        apply(tp);
    }

    private void apply(TextPaint tp) {
        boolean baseBold = blockStyle == EpubBook.Block.STYLE_H1
                || blockStyle == EpubBook.Block.STYLE_H2;
        boolean baseItalic = blockStyle == EpubBook.Block.STYLE_QUOTE;

        boolean bold = (baseBold || (mask & EpubBook.InlineSpan.BOLD) != 0)
                && (mask & EpubBook.InlineSpan.CLEAR_BOLD) == 0;
        boolean italic = (baseItalic || (mask & EpubBook.InlineSpan.ITALIC) != 0)
                && (mask & EpubBook.InlineSpan.CLEAR_ITALIC) == 0;
        boolean mono = (mask & EpubBook.InlineSpan.CODE) != 0;

        tp.setTypeface(styles.typefaceFor(mono, bold, italic));

        float baseSize = tp.getTextSize();
        float scale = 1f;
        if (mono) scale *= CODE_SCALE;
        if ((mask & EpubBook.InlineSpan.SMALL) != 0) scale *= SMALL_SCALE;
        if ((mask & EpubBook.InlineSpan.SUP) != 0) {
            scale *= SCRIPT_SCALE;
            tp.baselineShift -= Math.round(baseSize * SUP_SHIFT);
        } else if ((mask & EpubBook.InlineSpan.SUB) != 0) {
            scale *= SCRIPT_SCALE;
            tp.baselineShift += Math.round(baseSize * SUB_SHIFT);
        }
        if (scale != 1f) {
            tp.setTextSize(baseSize * scale);
        }

        if ((mask & EpubBook.InlineSpan.UNDERLINE) != 0) tp.setUnderlineText(true);
        if ((mask & EpubBook.InlineSpan.STRIKE) != 0) tp.setStrikeThruText(true);
    }
}
