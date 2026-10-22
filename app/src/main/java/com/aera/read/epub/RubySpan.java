package com.aera.read.epub;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

/**
 * Renders phonetic reading annotations ({@code <ruby>} / furigana / pinyin / bopomofo) directly
 * above their base characters on a single pass with zero offscreen layers.
 *
 * <p>Layout invariants:
 * <ul>
 *   <li>Horizontal advance expands beyond the base characters only when the annotation actually
 *       exceeds the base width, adding side clearance only between immediately adjacent
 *       {@link RubySpan}s so neighboring annotations never collide while ordinary kana/punctuation
 *       neighbors join naturally without artificial gaps.</li>
 *   <li>When wide annotations expand a multi-character CJK base word, base characters distribute
 *       across the slot using bounded JIS X 4051 {@code 1:2:1} spacing instead of leaving white
 *       gaps on both sides of the word.</li>
 *   <li>{@link #getSize} and {@link #requiredAscent} report the exact extra headroom above the
 *       base ascent so line 0 at the top of a page never clips and compact leading (125%) never
 *       collides with the line above.</li>
 * </ul>
 */
public final class RubySpan extends ReplacementSpan {

    /** Standard JIS X 4051 / W3C ruby-to-base ratio (50% of base em size). */
    public static final float RUBY_SCALE = 0.5f;

    /** Minimum readable annotation size on extreme miniature configurations. */
    private static final float MIN_RUBY_PX = 8f;

    /** Vertical breathing room between base glyph tops and annotation descenders (in base ems). */
    private static final float RUBY_GAP_EM = 0.06f;

    /** Half-gap added only between two abutting wide ruby runs so adjacent readings stay distinct. */
    private static final float ADJACENT_RUBY_PAD_EM = 0.08f;

    /** Maximum per-character JIS 1:2:1 expansion so extreme gikun phrases never drift apart. */
    private static final float MAX_CHAR_SPREAD_EM = 0.28f;

    private static final ThreadLocal<TextPaint> SCRATCH = new ThreadLocal<>() {
        @Override
        protected TextPaint initialValue() {
            return new TextPaint(Paint.ANTI_ALIAS_FLAG);
        }
    };

    private final String ruby;
    private final int maxWidth;

    public RubySpan(String ruby, int maxWidth) {
        this.ruby = ruby == null ? "" : ruby;
        this.maxWidth = maxWidth;
    }

    public String rubyText() {
        return ruby;
    }

    private TextPaint prepareRubyPaint(Paint basePaint) {
        TextPaint rp = SCRATCH.get();
        rp.set(basePaint);
        rp.bgColor = 0;
        rp.baselineShift = 0;
        rp.setUnderlineText(false);
        rp.setStrikeThruText(false);
        rp.setTextSize(Math.max(MIN_RUBY_PX, basePaint.getTextSize() * RUBY_SCALE));
        return rp;
    }

    /** Exact negative ascent required above the baseline so the annotation never clips. */
    public int requiredAscent(Paint basePaint) {
        if (ruby.isEmpty()) return (int) Math.floor(basePaint.ascent());
        TextPaint rp = prepareRubyPaint(basePaint);
        float gap = Math.max(1f, basePaint.getTextSize() * RUBY_GAP_EM);
        float topY = basePaint.ascent() - gap - rp.descent() + rp.ascent();
        return (int) Math.floor(topY - 1f);
    }

    private static float sideClearance(Paint basePaint, CharSequence text, int start, int end) {
        if (!(text instanceof Spanned spanned)) return 0f;
        float pad = 0f;
        float unit = Math.max(1.5f, basePaint.getTextSize() * ADJACENT_RUBY_PAD_EM);
        if (start > 0 && hasRubyAt(spanned, start - 1, start)) pad += unit;
        if (end < text.length() && hasRubyAt(spanned, end, end + 1)) pad += unit;
        return pad;
    }

    private static boolean hasRubyAt(Spanned spanned, int from, int to) {
        RubySpan[] spans = spanned.getSpans(from, to, RubySpan.class);
        for (RubySpan s : spans) {
            if (Math.max(from, spanned.getSpanStart(s)) < Math.min(to, spanned.getSpanEnd(s))) {
                return true;
            }
        }
        return false;
    }

    private float computeSlotWidth(Paint basePaint, CharSequence text, int start, int end,
                                   float baseWidth, float rubyWidth) {
        if (rubyWidth <= 0f) return baseWidth;
        float needed = rubyWidth + sideClearance(basePaint, text, start, end);
        float w = Math.max(baseWidth, needed);
        if (maxWidth > 0 && w > maxWidth) {
            w = Math.max(Math.min(baseWidth, maxWidth), maxWidth);
        }
        return w;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
            if (!ruby.isEmpty()) {
                int reqAscent = requiredAscent(paint);
                if (reqAscent < fm.ascent) fm.ascent = reqAscent;
                if (fm.ascent < fm.top) fm.top = fm.ascent;
            }
        }
        float baseWidth = paint.measureText(text, start, end);
        if (ruby.isEmpty()) return (int) Math.ceil(baseWidth);
        TextPaint rp = prepareRubyPaint(paint);
        float rubyWidth = rp.measureText(ruby);
        return (int) Math.ceil(computeSlotWidth(paint, text, start, end, baseWidth, rubyWidth));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        float baseWidth = paint.measureText(text, start, end);
        if (ruby.isEmpty()) {
            canvas.drawText(text, start, end, x, y, paint);
            return;
        }

        TextPaint rp = prepareRubyPaint(paint);
        rp.setColor(paint.getColor());

        float rubyWidth = rp.measureText(ruby);
        float slot = computeSlotWidth(paint, text, start, end, baseWidth, rubyWidth);

        // If an unusually long reading exceeds the column width, gently tighten the ruby run
        // rather than letting any glyph clip outside [x, x + slot].
        if (rubyWidth > slot && slot > 0f) {
            float fitSize = rp.getTextSize() * (slot / rubyWidth);
            if (fitSize >= MIN_RUBY_PX) {
                rp.setTextSize(fitSize);
                rubyWidth = rp.measureText(ruby);
            } else {
                rp.setTextSize(MIN_RUBY_PX);
                rubyWidth = rp.measureText(ruby);
                if (rubyWidth > slot) {
                    rp.setTextScaleX(slot / rubyWidth);
                    rubyWidth = rp.measureText(ruby);
                }
            }
        }

        float extra = slot - baseWidth;
        drawBaseRun(canvas, text, start, end, x, y, slot, baseWidth, extra, paint);

        float midX = x + slot * 0.5f;
        float gap = Math.max(1f, paint.getTextSize() * RUBY_GAP_EM);
        float rubyBaseline = y + paint.ascent() - gap - rp.descent();
        canvas.drawText(ruby, midX - rubyWidth * 0.5f, rubyBaseline, rp);
        rp.setTextScaleX(1f);
    }

    private static void drawBaseRun(Canvas canvas, CharSequence text, int start, int end,
                                    float x, int y, float slot, float baseWidth, float extra,
                                    Paint paint) {
        int cpCount = Character.codePointCount(text, start, end);
        if (extra <= 1f || cpCount < 2 || !isAllCjk(text, start, end)) {
            canvas.drawText(text, start, end, x + Math.max(0f, extra) * 0.5f, y, paint);
            return;
        }
        // Bounded JIS X 4051 1:2:1 spacing across multi-character CJK words with wide furigana.
        float maxTotalSpread = cpCount * paint.getTextSize() * MAX_CHAR_SPREAD_EM;
        float spread = Math.min(extra, maxTotalSpread);
        float unit = spread / (2f * cpCount);
        float cursor = x + (extra - spread) * 0.5f + unit;
        int i = start;
        while (i < end) {
            int cp = Character.codePointAt(text, i);
            int next = i + Character.charCount(cp);
            canvas.drawText(text, i, next, cursor, y, paint);
            cursor += paint.measureText(text, i, next) + 2f * unit;
            i = next;
        }
    }

    private static boolean isAllCjk(CharSequence text, int start, int end) {
        int i = start;
        while (i < end) {
            int cp = Character.codePointAt(text, i);
            if (!isCjkCodePoint(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isCjkCodePoint(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x323AF);
    }
}
