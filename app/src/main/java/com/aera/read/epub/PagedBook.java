package com.aera.read.epub;

import android.text.StaticLayout;

import java.util.ArrayList;
import java.util.List;

/** The fully paginated book for one content size + style combination. */
public class PagedBook {

    public final List<ChapterPages> chapters = new ArrayList<>();
    public int totalPages = 1;
    public int width;
    public int height;
    public TextStyles styles;
    public EpubBook source;
    private int[] chapterStarts = new int[0];

    public void setChapterStarts() {
        chapterStarts = new int[chapters.size()];
        int g = 0;
        for (int i = 0; i < chapters.size(); i++) {
            chapterStarts[i] = g;
            g += chapters.get(i).pages.size();
        }
    }

    public Page page(int global) {
        if (chapters.isEmpty()) {
            return new Page();
        }
        int clamped = Math.max(0, global);
        int idx = java.util.Arrays.binarySearch(chapterStarts, clamped);
        if (idx < 0) {
            idx = Math.max(0, -idx - 2);
        } else {
            // Skip past zero-page chapters sharing the same start index, if any.
            while (idx + 1 < chapterStarts.length && chapterStarts[idx + 1] <= clamped) idx++;
        }
        ChapterPages cp = chapters.get(idx);
        int local = Math.max(0, Math.min(cp.pages.size() - 1, clamped - chapterStarts[idx]));
        return cp.pages.get(local);
    }

    /** Page that contains the given chapter + character offset. */
    public int pageIndex(int chapter, int offset) {
        if (chapters.isEmpty()) return 0;
        chapter = Math.max(0, Math.min(chapters.size() - 1, chapter));
        ChapterPages cp = chapters.get(chapter);
        for (Page p : cp.pages) {
            if (offset >= p.startOffset && offset < Math.max(p.endOffset, p.startOffset + 1)) {
                return p.global;
            }
        }
        // offset outside every range (stale bookmark / clamped value): use the closest page
        int best = chapterStarts[chapter];
        for (Page p : cp.pages) {
            if (p.startOffset <= offset) best = p.global;
        }
        return best;
    }

    /**
     * Page to show when the reader's spot is {@code chapter}/{@code offset}.
     *
     * The page holding the spot is the safe answer, but after a re-flow that page can begin
     * most of a screen above the spot, and the reader is handed text they already read. So
     * the page boundary nearest to the spot wins: when the spot sits in the last part of a
     * page and the next page starts within a short hop of it, the next page is shown and the
     * spot lands at the top of the screen, where it was before the font changed.
     */
    public int pageForAnchor(int chapter, int offset) {
        int containing = pageIndex(chapter, offset);
        Page p = page(containing);
        int span = Math.max(1, p.endOffset - p.startOffset);
        int above = offset - p.startOffset;
        int next = containing + 1;
        if (next < totalPages && page(next).chapter == p.chapter) {
            int below = page(next).startOffset - offset;
            // hop forward only across a couple of lines, and only from the bottom of a page
            if (below >= 0 && below <= 120 && above * 2 > span) {
                return next;
            }
        }
        return containing;
    }

    /** Character offset of the text line at {@code y} (content pixels) on a page. */
    public int offsetAt(int globalPage, float y) {
        Page p = page(globalPage);
        float yy = Math.max(0, y);
        for (Segment seg : p.segments) {
            if (seg.isImage()) {
                if (yy < seg.y + seg.height) return p.startOffset;
                continue;
            }
            if (yy >= seg.y && yy <= seg.y + seg.height) {
                int line = seg.layout.getLineForVertical(Math.round(yy - seg.y));
                line = Math.max(seg.startLine, Math.min(seg.endLine - 1, line));
                return seg.block.charStart + seg.layout.getLineStart(line);
            }
        }
        // above everything on the page: the page starts here; below it: its last character
        if (yy <= 0f) return p.startOffset;
        return Math.max(p.startOffset, p.endOffset - 1);
    }

    /** Relative position of an offset inside its page, 0..1. */
    public static float fractionOf(Page p, int offset) {
        int span = Math.max(1, p.endOffset - p.startOffset);
        float f = (offset - p.startOffset) / (float) span;
        return Math.max(0f, Math.min(1f, f));
    }

    public int chapterStartPage(int chapter) {
        if (chapterStarts.length == 0) return 0;
        chapter = Math.max(0, Math.min(chapterStarts.length - 1, chapter));
        return chapterStarts[chapter];
    }

    public static class Page {
        public int chapter;
        public int chapterPage;
        public int global;
        public int startOffset;
        public int endOffset;
        public final List<Segment> segments = new ArrayList<>();
    }

    public static class Segment {
        public int y;
        public int height;
        public StaticLayout layout; // null for images
        public int startLine;
        public int endLine;
        public EpubBook.Block block;

        public boolean isImage() {
            return layout == null;
        }
    }

    public static class ChapterPages {
        public EpubBook.Chapter chapter;
        public int firstGlobal;
        public final List<Page> pages = new ArrayList<>();
    }
}
