package com.aera.read.epub;

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BulletSpan;
import android.text.style.LineHeightSpan;

import java.util.List;

/** Splits chapters into pages at the given content size. Pure text + inline-object measurement. */
public final class EpubPaginator {

    private EpubPaginator() {
    }

    public static PagedBook paginate(EpubBook book, TextStyles styles, int width, int height) {
        PagedBook out = new PagedBook();
        out.source = book;
        out.styles = styles;
        out.width = Math.max(width, 80);
        out.height = Math.max(height, 80);

        SpanSet spans = new SpanSet(styles);
        int global = 0;
        for (EpubBook.Chapter ch : book.chapters) {
            PagedBook.ChapterPages cp = paginateChapter(ch, styles, spans, out.width, out.height);
            cp.chapter = ch;
            cp.firstGlobal = global;
            for (int i = 0; i < cp.pages.size(); i++) {
                PagedBook.Page page = cp.pages.get(i);
                page.chapter = ch.index;
                page.chapterPage = i;
                page.global = global + i;
            }
            global += cp.pages.size();
            out.chapters.add(cp);
        }
        out.totalPages = Math.max(global, 1);
        out.setChapterStarts();
        return out;
    }

    private static PagedBook.ChapterPages paginateChapter(EpubBook.Chapter ch, TextStyles st,
                                                          SpanSet spans, int width, int height) {
        PagedBook.ChapterPages cp = new PagedBook.ChapterPages();
        List<PagedBook.Page> pages = cp.pages;

        PagedBook.Page cur = new PagedBook.Page();
        int y = 0;
        boolean hasContent = false;
        boolean firstBlock = true;

        for (EpubBook.Block block : ch.blocks) {
            if (block.type == EpubBook.Block.IMAGE) {
                if (block.imgW <= 0 || block.imgH <= 0) continue;
                float scale = Math.min(1f, width / (float) block.imgW);
                float imgH = block.imgH * scale;
                if (imgH > height) imgH = height;
                int h = Math.round(imgH);
                if (hasContent && y + h > height) {
                    closePage(cur, block.charStart);
                    pages.add(cur);
                    cur = new PagedBook.Page();
                    y = 0;
                    hasContent = false;
                }
                PagedBook.Segment seg = new PagedBook.Segment();
                seg.block = block;
                seg.y = y;
                seg.height = h;
                if (!hasContent) {
                    cur.startOffset = block.charStart;
                    hasContent = true;
                }
                cur.segments.add(seg);
                y += h + Math.round(st.paraGap);
                firstBlock = false;
                continue;
            }

            if (block.text.isEmpty()) continue;
            StaticLayout layout = build(block, st, spans, width);
            if (layout == null || layout.getLineCount() == 0) continue;

            int gapAfter = Math.round(st.gapAfter(block.style));
            int gapBefore = firstBlock ? 0 : Math.round(st.gapBefore(block.style));
            if (hasContent && gapBefore > 0 && y + gapBefore < height) {
                y += gapBefore;
            }
            firstBlock = false;

            int line = 0;
            int lineCount = layout.getLineCount();
            while (line < lineCount) {
                int top = layout.getLineTop(line);
                int fit = 0;
                while (line + fit < lineCount) {
                    int bottom = layout.getLineBottom(line + fit) - top;
                    if (line + fit == lineCount - 1) bottom += gapAfter;
                    if (y + bottom <= height) fit++;
                    else break;
                }
                if (fit == 0) {
                    if (!hasContent) {
                        fit = 1; // single-line taller than page: keep it to guarantee progress
                    } else {
                        closePage(cur, block.charStart + layout.getLineStart(line));
                        pages.add(cur);
                        cur = new PagedBook.Page();
                        y = 0;
                        hasContent = false;
                        continue;
                    }
                }
                PagedBook.Segment seg = new PagedBook.Segment();
                seg.block = block;
                seg.layout = layout;
                seg.startLine = line;
                seg.endLine = line + fit;
                seg.y = y;
                seg.height = layout.getLineBottom(line + fit - 1) - top;
                if (!hasContent) {
                    cur.startOffset = block.charStart + layout.getLineStart(line);
                    hasContent = true;
                }
                cur.segments.add(seg);
                y += seg.height + (line + fit == lineCount ? gapAfter : 0);
                line += fit;
                if (line < lineCount) {
                    closePage(cur, block.charStart + layout.getLineStart(line));
                    pages.add(cur);
                    cur = new PagedBook.Page();
                    y = 0;
                    hasContent = false;
                }
            }
        }

        if (hasContent) {
            closePage(cur, ch.charCount);
            pages.add(cur);
        }
        if (pages.isEmpty()) {
            PagedBook.Page p = new PagedBook.Page();
            p.startOffset = 0;
            p.endOffset = ch.charCount;
            pages.add(p);
        } else {
            for (int i = 0; i < pages.size() - 1; i++) {
                pages.get(i).endOffset = pages.get(i + 1).startOffset;
            }
            pages.get(pages.size() - 1).endOffset = ch.charCount;
        }
        return cp;
    }

    private static void closePage(PagedBook.Page page, int endOffset) {
        page.endOffset = endOffset;
        if (page.segments.isEmpty()) page.startOffset = Math.min(page.startOffset, endOffset);
    }

    @SuppressLint("WrongConstant")
    private static StaticLayout build(EpubBook.Block block, TextStyles st, SpanSet spans,
                                      int width) {
        TextPaint paint = st.paintFor(block.style);
        boolean heading = st.isHeading(block.style);
        int avail = Math.max(60, width - Math.round(st.indentFor(block.style)));
        SpannableString ss = new SpannableString(block.text);
        int len = ss.length();

        if (block.spans != null) {
            for (EpubBook.InlineSpan s : block.spans) {
                if (s.start >= 0 && s.start < s.end && s.end <= len && s.mask != 0) {
                    ss.setSpan(new InlineStyleSpan(st, block.style, s.mask),
                            s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        if (block.inlineImages != null) {
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            float textSize = paint.getTextSize();
            for (EpubBook.InlineImage img : block.inlineImages) {
                if (img.pos >= 0 && img.pos + 1 <= len) {
                    ss.setSpan(new InlineImageSpan(img, textSize, fm.ascent, fm.descent, avail),
                            img.pos, img.pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        if (block.rubies != null) {
            for (EpubBook.Ruby r : block.rubies) {
                if (r.start >= 0 && r.start < r.end && r.end <= len) {
                    ss.setSpan(new RubySpan(r.text, avail), r.start, r.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        ExactLineHeightSpan lh = spans.lineHeightFor(block.style);
        boolean hasExtraVertical = block.rubies != null || block.inlineImages != null
                || hasShiftedScript(block.spans);
        ss.setSpan(hasExtraVertical ? lh.withVerticalSpans() : lh, 0, len,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (block.style == EpubBook.Block.STYLE_LIST) {
            ss.setSpan(spans.bullet, 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return StaticLayout.Builder.obtain(ss, 0, len, paint, avail)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setAlignment(heading ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private static boolean hasShiftedScript(List<EpubBook.InlineSpan> spans) {
        if (spans == null) return false;
        for (EpubBook.InlineSpan s : spans) {
            if ((s.mask & (EpubBook.InlineSpan.SUP | EpubBook.InlineSpan.SUB)) != 0) return true;
        }
        return false;
    }

    private static final class SpanSet {
        final ExactLineHeightSpan body;
        final ExactLineHeightSpan h1;
        final ExactLineHeightSpan h2;
        final ExactLineHeightSpan quote;
        final BulletSpan bullet;

        SpanSet(TextStyles st) {
            body = new ExactLineHeightSpan(st.body, st.lineBoxPx(st.body), false);
            h1 = new ExactLineHeightSpan(st.h1, st.lineBoxPx(st.h1), false);
            h2 = new ExactLineHeightSpan(st.h2, st.lineBoxPx(st.h2), false);
            quote = new ExactLineHeightSpan(st.quote, st.lineBoxPx(st.quote), false);
            bullet = new BulletSpan(Math.max(8, Math.round(st.listIndent)));
        }

        ExactLineHeightSpan lineHeightFor(int style) {
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
    }

    /**
     * Enforces the target line box on every line while guaranteeing full vertical clearance
     * for ruby annotations, inline images/gaiji, and superscript/subscript runs.
     * Plain paragraphs skip {@code Spanned.getSpans} entirely on the hot pagination path.
     */
    private static final class ExactLineHeightSpan implements LineHeightSpan {
        private final TextPaint basePaint;
        private final int target;
        private final int baseAscent;
        private final int baseDescent;
        private final boolean checkVerticalSpans;
        private ExactLineHeightSpan verticalVariant;

        ExactLineHeightSpan(TextPaint paint, int target, boolean checkVerticalSpans) {
            this.basePaint = paint;
            this.target = target;
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            this.baseAscent = fm.ascent;
            this.baseDescent = fm.descent;
            this.checkVerticalSpans = checkVerticalSpans;
        }

        ExactLineHeightSpan withVerticalSpans() {
            if (checkVerticalSpans) return this;
            if (verticalVariant == null) {
                verticalVariant = new ExactLineHeightSpan(basePaint, target, true);
            }
            return verticalVariant;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int lineHeight, Paint.FontMetricsInt fm) {
            if (!checkVerticalSpans || !(text instanceof Spanned)) {
                applyNominal(fm);
                return;
            }

            Spanned spanned = (Spanned) text;
            boolean expanded = false;
            int minAscent = Integer.MAX_VALUE;
            int maxDescent = Integer.MIN_VALUE;

            RubySpan[] rubies = spanned.getSpans(start, end, RubySpan.class);
            for (RubySpan ruby : rubies) {
                if (Math.max(start, spanned.getSpanStart(ruby))
                        < Math.min(end, spanned.getSpanEnd(ruby))) {
                    expanded = true;
                    minAscent = Math.min(minAscent, ruby.requiredAscent(basePaint));
                }
            }

            InlineImageSpan[] imgs = spanned.getSpans(start, end, InlineImageSpan.class);
            for (InlineImageSpan img : imgs) {
                if (Math.max(start, spanned.getSpanStart(img))
                        < Math.min(end, spanned.getSpanEnd(img))) {
                    expanded = true;
                    minAscent = Math.min(minAscent, img.requiredAscent());
                    maxDescent = Math.max(maxDescent, img.requiredDescent());
                }
            }

            InlineStyleSpan[] styles = spanned.getSpans(start, end, InlineStyleSpan.class);
            for (InlineStyleSpan st : styles) {
                if (st.hasVerticalShift()
                        && Math.max(start, spanned.getSpanStart(st))
                        < Math.min(end, spanned.getSpanEnd(st))) {
                    expanded = true;
                    minAscent = Math.min(minAscent, st.requiredAscent(basePaint));
                    maxDescent = Math.max(maxDescent, st.requiredDescent(basePaint));
                }
            }

            if (!expanded) {
                applyNominal(fm);
                return;
            }

            int baseOrigin = baseDescent - baseAscent;
            int delta = target - baseOrigin;
            int half = delta / 2;
            int normalAscent = baseAscent - (delta - half);
            int normalDescent = baseDescent + half;
            fm.ascent = Math.min(normalAscent, minAscent);
            fm.descent = Math.max(Math.max(fm.descent, normalDescent), maxDescent);
            fm.top = Math.min(fm.top, fm.ascent);
            fm.bottom = Math.max(fm.bottom, fm.descent);
        }

        private void applyNominal(Paint.FontMetricsInt fm) {
            int origin = fm.descent - fm.ascent;
            if (origin <= 0) return;
            int delta = target - origin;
            int half = delta / 2;
            fm.ascent -= delta - half;
            fm.descent += half;
            fm.top = Math.min(fm.top, fm.ascent);
            fm.bottom = Math.max(fm.bottom, fm.descent);
        }
    }
}
