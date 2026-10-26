package com.aera.read.epub;

import com.aera.read.io.ZipArchive;

import java.util.ArrayList;
import java.util.List;

/** Parsed EPUB: metadata, chapters with styled blocks, inline spans/images, and contents. */
public class EpubBook {

    public String title = "";
    public String author = "";
    public byte[] cover;
    public final List<Chapter> chapters = new ArrayList<>();
    public final List<TocEntry> toc = new ArrayList<>();
    public ZipArchive zip;

    public static class Chapter {
        public int index;
        public String path = "";
        public String title = "";
        public final List<Block> blocks = new ArrayList<>();
        public int charCount;
    }

    public static class Block {
        public static final int TEXT = 0;
        public static final int IMAGE = 1;

        public static final int STYLE_BODY = 0;
        public static final int STYLE_H1 = 1;
        public static final int STYLE_H2 = 2;
        public static final int STYLE_QUOTE = 3;
        public static final int STYLE_LIST = 4;

        public int type = TEXT;
        public String text = "";
        public int style = STYLE_BODY;
        public List<Ruby> rubies;
        public List<InlineSpan> spans;
        public List<InlineImage> inlineImages;
        public String imagePath = "";
        public int imgW;
        public int imgH;
        public int charStart;

        public static Block text(String text, int style) {
            return richText(text, style, null, null, null);
        }

        public static Block text(String text, int style, List<Ruby> rubies) {
            return richText(text, style, rubies, null, null);
        }

        public static Block richText(String text, int style, List<Ruby> rubies,
                                     List<InlineSpan> spans, List<InlineImage> inlineImages) {
            Block b = new Block();
            b.type = TEXT;
            b.text = text;
            b.style = style;
            if (rubies != null && !rubies.isEmpty()) b.rubies = rubies;
            if (spans != null && !spans.isEmpty()) b.spans = spans;
            if (inlineImages != null && !inlineImages.isEmpty()) b.inlineImages = inlineImages;
            return b;
        }

        public static Block image(String path, int w, int h) {
            Block b = new Block();
            b.type = IMAGE;
            b.imagePath = path;
            b.imgW = w;
            b.imgH = h;
            return b;
        }
    }

    /**
     * Inline formatting run over {@code [start, end)} of a {@link Block#text}.
     * Bits 0..7 set inline styles; bits 8..9 explicitly clear inherited bold/italic via CSS.
     */
    public static final class InlineSpan {
        public static final int BOLD = 1;
        public static final int ITALIC = 1 << 1;
        public static final int UNDERLINE = 1 << 2;
        public static final int STRIKE = 1 << 3;
        public static final int CODE = 1 << 4;
        public static final int SUP = 1 << 5;
        public static final int SUB = 1 << 6;
        public static final int SMALL = 1 << 7;
        public static final int CLEAR_BOLD = 1 << 8;
        public static final int CLEAR_ITALIC = 1 << 9;

        public final int start;
        public final int end;
        public final int mask;

        public InlineSpan(int start, int end, int mask) {
            this.start = start;
            this.end = end;
            this.mask = mask;
        }
    }

    /**
     * Inline image / gaiji anchored at {@code [pos, pos + 1)} (the {@code \uFFFC} placeholder)
     * inside {@link Block#text}. Explicit CSS/HTML dimensions ({@code emW}/{@code emH} or
     * {@code pxW}/{@code pxH}) are optional; {@code 0} means derive from intrinsic dimensions.
     */
    public static final class InlineImage {
        public final int pos;
        public final String path;
        public final int imgW;
        public final int imgH;
        public final float emW;
        public final float emH;
        public final float pxW;
        public final float pxH;

        public InlineImage(int pos, String path, int imgW, int imgH,
                           float emW, float emH, float pxW, float pxH) {
            this.pos = pos;
            this.path = path;
            this.imgW = imgW;
            this.imgH = imgH;
            this.emW = emW;
            this.emH = emH;
            this.pxW = pxW;
            this.pxH = pxH;
        }
    }

    /** Phonetic reading annotation (furigana / pinyin / bopomofo) over [start, end) of a block. */
    public static final class Ruby {
        public final int start;
        public final int end;
        public final String text;

        public Ruby(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    public static class TocEntry {
        public final int chapter;
        public final int level;
        public final String title;

        public TocEntry(int chapter, int level, String title) {
            this.chapter = chapter;
            this.level = level;
            this.title = title;
        }
    }

    public void close() {
        if (zip != null) {
            try {
                zip.close();
            } catch (Exception ignored) {
            }
            zip = null;
        }
    }
}
