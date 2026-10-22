package com.aera.read.epub;

import com.aera.read.io.ZipArchive;

import java.util.ArrayList;
import java.util.List;

/** Parsed EPUB: metadata, chapters with flattened text blocks, table of contents. */
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
        public String imagePath = "";
        public int imgW;
        public int imgH;
        public int charStart;

        public static Block text(String text, int style) {
            return text(text, style, null);
        }

        public static Block text(String text, int style, List<Ruby> rubies) {
            Block b = new Block();
            b.type = TEXT;
            b.text = text;
            b.style = style;
            if (rubies != null && !rubies.isEmpty()) b.rubies = rubies;
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
