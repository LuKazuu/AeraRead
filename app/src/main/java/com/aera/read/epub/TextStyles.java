package com.aera.read.epub;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;

import com.aera.read.data.ReaderPrefs;
import com.aera.read.util.Fonts;
import com.aera.read.util.Ui;

/** Paints and spacing metrics derived from user settings. Shared by all pages of a layout. */
public class TextStyles {

    public final TextPaint body = new TextPaint();
    public final TextPaint h1 = new TextPaint();
    public final TextPaint h2 = new TextPaint();
    public final TextPaint quote = new TextPaint();

    public final float sizePx;
    public final int linePct;
    /** Extra leading in px, normalized so every font lands on the same target em box. */
    public final float lineExtra;
    public final float paraGap;
    public final float headingGap;
    public final float quoteIndent;
    public final float listIndent;

    private TextStyles(Context c, ReaderPrefs prefs) {
        sizePx = Ui.sp(c, prefs.sizeSp);
        linePct = prefs.linePct;
        paraGap = sizePx * 0.40f * (linePct / 100f);
        headingGap = sizePx * 0.32f;
        quoteIndent = Ui.dp(c, 18);
        listIndent = Ui.dp(c, 16);

        Typeface regular = Fonts.get(c, prefs.font, false, false);
        Typeface bold = Fonts.get(c, prefs.font, true, false);
        Typeface italic = Fonts.get(c, prefs.font, false, true);

        setup(body, regular, sizePx);
        setup(h1, bold, sizePx * 1.5f);
        setup(h2, bold, sizePx * 1.2f);
        setup(quote, italic, sizePx * 0.97f);

        lineExtra = lineExtraFor(body);
    }

    /** Target line box height in pixels for a paint at the current line-spacing setting. */
    public int lineBoxPx(TextPaint p) {
        return Math.max(1, Math.round(p.getTextSize() * linePct / 100f));
    }

    /** Leading delta relative to a paint's own primary font metrics. */
    public float lineExtraFor(TextPaint p) {
        return p.getTextSize() * linePct / 100f - p.getFontMetrics(null);
    }

    public static TextStyles create(Context c, ReaderPrefs prefs) {
        return new TextStyles(c, prefs);
    }

    private static void setup(TextPaint p, Typeface tf, float size) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setAntiAlias(true);
        p.setSubpixelText(true);
        p.setColor(0xFF000000);
    }

    public TextPaint paintFor(int style) {
        switch (style) {
            case EpubBook.Block.STYLE_H1:
                return h1;
            case EpubBook.Block.STYLE_H2:
                return h2;
            case EpubBook.Block.STYLE_QUOTE:
                return quote;
            default:
                return body;
        }
    }

    public float gapAfter(int style) {
        switch (style) {
            case EpubBook.Block.STYLE_H1:
                return sizePx * 0.55f;
            case EpubBook.Block.STYLE_H2:
                return sizePx * 0.42f;
            default:
                return paraGap;
        }
    }

    public float gapBefore(int style) {
        switch (style) {
            case EpubBook.Block.STYLE_H1:
                return sizePx * 0.7f;
            case EpubBook.Block.STYLE_H2:
                return headingGap;
            default:
                return 0f;
        }
    }

    public float indentFor(int style) {
        return style == EpubBook.Block.STYLE_QUOTE ? quoteIndent : 0f;
    }

    /** Layouts bake paint colors at build time; the view tints glyphs to the theme. */
    public void setColor(int color) {
        body.setColor(color);
        h1.setColor(color);
        h2.setColor(color);
        quote.setColor(color);
    }

    public boolean isHeading(int style) {
        return style == EpubBook.Block.STYLE_H1 || style == EpubBook.Block.STYLE_H2;
    }
}
