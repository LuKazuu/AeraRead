package com.aera.read.epub;

import android.graphics.BitmapFactory;

import com.aera.read.io.ZipArchive;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EPUB 2/3 parser. Flattens each spine document into styled text blocks with exact character
 * offsets, so reading positions survive pagination changes. Reads through {@link ZipArchive},
 * which works on any seekable source (including a content-provider file descriptor).
 */
public final class EpubParser {

    private final ZipArchive zip;
    private Map<String, String[]> items = new HashMap<>();

    public EpubParser(ZipArchive zip) {
        this.zip = zip;
    }

    /** Metadata + cover only: fast enough to run for every file during a folder scan. */
    public EpubMetadata readMetadata(String fallbackTitle) throws IOException {
        EpubMetadata meta = new EpubMetadata();
        Document opf = readOpf();
        meta.title = firstMetaText(opf, "title");
        meta.author = firstMetaText(opf, "creator");
        if (meta.title.isEmpty()) meta.title = fallbackTitle;
        String coverPath = findCoverPath(opf);
        if (coverPath != null) {
            try {
                meta.cover = zip.read(coverPath);
            } catch (Exception ignored) {
            }
        }
        return meta;
    }

    public EpubBook parse(String fallbackTitle) throws IOException {
        EpubBook book = new EpubBook();
        book.zip = zip;

        Document opf = readOpf();
        book.title = firstMetaText(opf, "title");
        book.author = firstMetaText(opf, "creator");
        if (book.title.isEmpty()) book.title = fallbackTitle;

        String coverPath = findCoverPath(opf);
        if (coverPath != null) {
            try {
                book.cover = zip.read(coverPath);
            } catch (Exception ignored) {
            }
        }

        for (String id : spineIds(opf)) {
            String[] item = items.get(id);
            String path = item[0];
            String media = item[1];
            if (!"application/xhtml+xml".equals(media) && !media.contains("html")
                    && !path.matches("(?i).*\\.(x?html?|xml)$")) {
                continue;
            }
            String html;
            try {
                byte[] bytes = zip.read(path);
                if (bytes == null) continue;
                html = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            Document doc = Jsoup.parse(html);
            EpubBook.Chapter chapter = new EpubBook.Chapter();
            chapter.index = book.chapters.size();
            chapter.path = path;
            if (doc.body() != null) walk(doc.body(), dirOf(path), chapter.blocks);
            assignOffsets(chapter);
            book.chapters.add(chapter);
        }
        if (book.chapters.isEmpty()) throw new IOException("No readable chapters");

        book.toc.addAll(parseNav(book));
        if (book.toc.isEmpty()) book.toc.addAll(parseNcx(book));

        // Chapter names come from the table of contents only. When a book ships no
        // contents we show none rather than inventing "Section N" labels that do not
        // match what the reader sees on the page.
        Map<Integer, String> tocTitles = new HashMap<>();
        for (EpubBook.TocEntry t : book.toc) {
            if (!tocTitles.containsKey(t.chapter) && !t.title.isEmpty()) {
                tocTitles.put(t.chapter, t.title);
            }
        }
        for (EpubBook.Chapter chapter : book.chapters) {
            String title = tocTitles.get(chapter.index);
            if (title == null || title.isEmpty()) title = firstHeading(chapter);
            chapter.title = title == null ? "" : title;
        }
        return book;
    }

    // ------------------------------------------------------------------ package

    private Document readOpf() throws IOException {
        byte[] container = zip.read("META-INF/container.xml");
        String opfPath = null;
        if (container != null) {
            Document doc = Jsoup.parse(new String(container, StandardCharsets.UTF_8), "",
                    Parser.xmlParser());
            for (Element el : doc.getAllElements()) {
                if ("rootfile".equals(el.normalName())) {
                    String path = el.attr("full-path");
                    if (!path.isEmpty()) {
                        opfPath = normalize(path);
                        break;
                    }
                }
            }
        }
        if (opfPath == null) throw new IOException("No package document");
        byte[] bytes = zip.read(opfPath);
        if (bytes == null) throw new IOException("Missing package document");
        Document opf = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "",
                Parser.xmlParser());

        String base = dirOf(opfPath);
        items = new HashMap<>();
        for (Element el : opf.getAllElements()) {
            if (!"item".equals(el.normalName())) continue;
            String href = el.attr("href");
            String id = el.attr("id");
            if (href.isEmpty() || id.isEmpty()) continue;
            items.put(id, new String[]{normalize(base + href),
                    el.attr("media-type").toLowerCase(Locale.ROOT),
                    el.attr("properties").toLowerCase(Locale.ROOT)});
        }
        return opf;
    }

    private List<String> spineIds(Document opf) throws IOException {
        List<String> spine = new ArrayList<>();
        for (Element el : opf.getAllElements()) {
            if (!"itemref".equals(el.normalName())) continue;
            String idref = el.attr("idref");
            if (items.containsKey(idref)) spine.add(idref);
        }
        if (spine.isEmpty()) throw new IOException("Empty spine");
        return spine;
    }

    private String findCoverPath(Document opf) {
        for (String[] item : items.values()) {
            if (item[2].contains("cover-image")) return item[0];
        }
        for (Element el : opf.getAllElements()) {
            if ("meta".equals(el.normalName()) && "cover".equals(el.attr("name"))) {
                String[] item = items.get(el.attr("content"));
                if (item != null) return item[0];
            }
        }
        for (String[] item : items.values()) {
            String lower = item[0].toLowerCase(Locale.ROOT);
            if (lower.contains("cover") && item[1].startsWith("image/")) return item[0];
        }
        return null;
    }

    private static String firstMetaText(Document opf, String localName) {
        for (Element el : opf.getAllElements()) {
            String name = el.normalName();
            if (name.equals(localName) || name.endsWith(":" + localName)) {
                Element parent = el.parent();
                if (parent != null) {
                    String pn = parent.normalName();
                    if (pn.equals("metadata") || pn.endsWith(":metadata")) return norm(el.text());
                }
            }
        }
        return "";
    }

    private String firstHeading(EpubBook.Chapter chapter) {
        for (EpubBook.Block b : chapter.blocks) {
            if (b.type == EpubBook.Block.TEXT
                    && (b.style == EpubBook.Block.STYLE_H1 || b.style == EpubBook.Block.STYLE_H2)
                    && !b.text.isEmpty()) {
                return b.text;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ toc

    private List<EpubBook.TocEntry> parseNav(EpubBook book) {
        List<EpubBook.TocEntry> out = new ArrayList<>();
        String navPath = null;
        for (String[] item : items.values()) {
            if (item[2].contains("nav") && item[1].contains("html")) {
                navPath = item[0];
                break;
            }
        }
        if (navPath == null) return out;
        try {
            byte[] bytes = zip.read(navPath);
            if (bytes == null) return out;
            Document doc = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
            Element nav = null;
            for (Element n : doc.select("nav")) {
                String type = n.attr("epub:type");
                if (type.isEmpty()) type = n.attr("type");
                if (type.toLowerCase(Locale.ROOT).contains("toc")) {
                    nav = n;
                    break;
                }
            }
            if (nav == null) nav = doc.selectFirst("nav");
            if (nav == null) return out;
            Element ol = nav.selectFirst("ol");
            if (ol != null) parseOl(ol, dirOf(navPath), book, out, 1);
        } catch (Exception ignored) {
        }
        return out;
    }

    private void parseOl(Element ol, String base, EpubBook book, List<EpubBook.TocEntry> out,
                         int depth) {
        if (depth > 3) return;
        for (Element li : ol.children()) {
            if (!"li".equals(li.normalName())) continue;
            Element anchor = null;
            for (Element c : li.children()) {
                if ("a".equals(c.normalName())) {
                    anchor = c;
                    break;
                }
            }
            if (anchor != null) {
                String title = plainTextWithoutRuby(anchor);
                String href = anchor.attr("href").trim();
                if (!title.isEmpty() && !href.isEmpty()) {
                    int chapter = chapterForHref(book, base, href);
                    if (chapter >= 0) out.add(new EpubBook.TocEntry(chapter, depth, title));
                }
            }
            for (Element sub : li.children()) {
                if ("ol".equals(sub.normalName()) || "ul".equals(sub.normalName())) {
                    parseOl(sub, base, book, out, depth + 1);
                }
            }
        }
    }

    private List<EpubBook.TocEntry> parseNcx(EpubBook book) {
        List<EpubBook.TocEntry> out = new ArrayList<>();
        String ncxPath = null;
        for (String[] item : items.values()) {
            if (item[1].contains("dtbncx")) {
                ncxPath = item[0];
                break;
            }
        }
        if (ncxPath == null) return out;
        try {
            byte[] bytes = zip.read(ncxPath);
            if (bytes == null) return out;
            Document doc = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "",
                    Parser.xmlParser());
            String base = dirOf(ncxPath);
            for (Element navPoint : doc.getAllElements()) {
                if (!"navpoint".equals(navPoint.normalName())) continue;
                String title = "";
                String src = "";
                for (Element child : navPoint.getAllElements()) {
                    String name = child.normalName();
                    if (name.equals("text") && title.isEmpty()) title = norm(child.text());
                    if (name.equals("content") && src.isEmpty()) src = child.attr("src");
                }
                if (title.isEmpty() || src.isEmpty()) continue;
                int depth = 1;
                Element parent = navPoint.parent();
                while (parent != null) {
                    if ("navpoint".equals(parent.normalName())) depth++;
                    parent = parent.parent();
                }
                int chapter = chapterForHref(book, base, src);
                if (chapter >= 0) {
                    out.add(new EpubBook.TocEntry(chapter, Math.min(depth, 3), title));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private int chapterForHref(EpubBook book, String base, String href) {
        String path = href;
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        if (path.isEmpty()) return -1;
        String full = normalize(base + path);
        for (EpubBook.Chapter chapter : book.chapters) {
            if (chapter.path.equalsIgnoreCase(full)) return chapter.index;
        }
        return -1;
    }

    // ------------------------------------------------------------------ content

    private static final class InlineBuf {
        final StringBuilder sb = new StringBuilder();
        final List<EpubBook.Ruby> rubies = new ArrayList<>();
        boolean trailingLineBreakSpace;

        void flush(int style, List<EpubBook.Block> out) {
            int n = sb.length();
            int start = 0;
            int end = n;
            while (start < end && sb.charAt(start) <= ' ') start++;
            while (end > start && sb.charAt(end - 1) <= ' ') end--;
            if (start < end) {
                String text = sb.substring(start, end);
                int len = text.length();
                List<EpubBook.Ruby> shifted = null;
                if (!rubies.isEmpty()) {
                    shifted = new ArrayList<>(rubies.size());
                    for (EpubBook.Ruby r : rubies) {
                        int rs = r.start - start;
                        int re = r.end - start;
                        if (rs < 0) rs = 0;
                        if (re > len) re = len;
                        if (rs < re && !r.text.isEmpty()) {
                            shifted.add(new EpubBook.Ruby(rs, re, r.text));
                        }
                    }
                }
                out.add(EpubBook.Block.text(text, style, shifted));
            }
            sb.setLength(0);
            rubies.clear();
            trailingLineBreakSpace = false;
        }
    }

    private void walk(Element parent, String baseDir, List<EpubBook.Block> out) {
        InlineBuf inline = new InlineBuf();
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode) {
                appendText(inline, ((TextNode) node).getWholeText(),
                        EpubBook.Block.STYLE_BODY, out);
                continue;
            }
            if (!(node instanceof Element)) continue;
            Element child = (Element) node;
            String tag = child.normalName();
            if (isSkippedTag(tag) || isHidden(child)) continue;

            if (tag.equals("br")) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                continue;
            }
            if (isImageEl(child)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                addImage(child, baseDir, out);
                continue;
            }
            if (tag.equals("svg")) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                walk(child, baseDir, out);
                continue;
            }
            if (isTextBlock(tag)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                emitTextBlocks(child, styleFor(tag), out);
                if (hasImages(child)) walkImages(child, baseDir, out);
                continue;
            }
            if (tag.equals("blockquote") || tag.equals("figure")) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                if (hasNestedBlocks(child)) {
                    walk(child, baseDir, out);
                } else {
                    emitTextBlocks(child, EpubBook.Block.STYLE_QUOTE, out);
                    if (hasImages(child)) walkImages(child, baseDir, out);
                }
                continue;
            }
            if (isBlockContainer(tag)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                walk(child, baseDir, out);
                continue;
            }
            collectInline(child, inline, EpubBook.Block.STYLE_BODY, out);
            if (hasImages(child)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                walkImages(child, baseDir, out);
            }
        }
        inline.flush(EpubBook.Block.STYLE_BODY, out);
    }

    private static boolean hasNestedBlocks(Element el) {
        for (Element c : el.children()) {
            String name = c.normalName();
            if (isTextBlock(name) || isBlockContainer(name)
                    || name.equals("blockquote") || name.equals("figure")) {
                return true;
            }
        }
        return false;
    }

    private void walkImages(Element parent, String baseDir, List<EpubBook.Block> out) {
        for (Element el : parent.children()) {
            if (isHidden(el)) continue;
            if (isImageEl(el)) addImage(el, baseDir, out);
            else walkImages(el, baseDir, out);
        }
    }

    private static void emitTextBlocks(Element el, int style, List<EpubBook.Block> out) {
        InlineBuf buf = new InlineBuf();
        collectInline(el, buf, style, out);
        buf.flush(style, out);
    }

    private static void collectInline(Element parent, InlineBuf buf, int style,
                                      List<EpubBook.Block> out) {
        if ("ruby".equals(parent.normalName())) {
            appendRuby(parent, buf, style, out);
            return;
        }
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode) {
                appendText(buf, ((TextNode) node).getWholeText(), style, out);
            } else if (node instanceof Element) {
                Element child = (Element) node;
                String tag = child.normalName();
                if (isSkippedTag(tag) || isHidden(child) || isImageEl(child)) continue;
                if (tag.equals("br")) {
                    buf.flush(style, out);
                } else if (tag.equals("ruby")) {
                    appendRuby(child, buf, style, out);
                } else {
                    collectInline(child, buf, style, out);
                }
            }
        }
    }

    /**
     * Parses HTML5 and W3C XHTML {@code <ruby>} annotations ({@code <rb>}, {@code <rbc>},
     * {@code <rt>}, {@code <rtc>}, {@code <rp>}), pairing single or multi-run base segments
     * with their phonetic readings while cleanly handling malformed or empty tags.
     */
    private static void appendRuby(Element rubyEl, InlineBuf buf, int style,
                                   List<EpubBook.Block> out) {
        List<String> bases = new ArrayList<>(2);
        List<String> rts = new ArrayList<>(2);
        collectRubyParts(rubyEl, bases, rts, buf, style, out);
        flushRubyGroup(bases, rts, buf, style, out);
    }

    private static void collectRubyParts(Element container, List<String> bases, List<String> rts,
                                         InlineBuf outBuf, int style, List<EpubBook.Block> out) {
        StringBuilder looseBase = new StringBuilder();
        for (Node node : container.childNodes()) {
            if (node instanceof TextNode) {
                String chunk = normRubyPart(((TextNode) node).getWholeText());
                if (!chunk.isEmpty()) {
                    if (!rts.isEmpty()) {
                        flushRubyGroup(bases, rts, outBuf, style, out);
                    }
                    looseBase.append(chunk);
                }
                continue;
            }
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            String tag = el.normalName();
            if (isSkippedTag(tag) || isHidden(el) || isImageEl(el)) continue;

            if (tag.equals("br")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                flushRubyGroup(bases, rts, outBuf, style, out);
                outBuf.flush(style, out);
                continue;
            }
            if (tag.equals("rbc")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, out);
                collectRubyParts(el, bases, rts, outBuf, style, out);
                continue;
            }
            if (tag.equals("rb")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, out);
                String rbText = norm(plainTextWithoutRuby(el));
                if (!rbText.isEmpty()) bases.add(rbText);
                continue;
            }
            if (tag.equals("rtc")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                for (Node rtcChild : el.childNodes()) {
                    if (rtcChild instanceof Element) {
                        Element re = (Element) rtcChild;
                        if (isSkippedTag(re.normalName()) || isHidden(re)) continue;
                        if ("rt".equals(re.normalName())) {
                            rts.add(norm(plainTextWithoutRuby(re)));
                        }
                    } else if (rtcChild instanceof TextNode) {
                        String t = norm(((TextNode) rtcChild).getWholeText());
                        if (!t.isEmpty()) rts.add(t);
                    }
                }
                continue;
            }
            if (tag.equals("rt")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                rts.add(norm(plainTextWithoutRuby(el)));
                continue;
            }
            if (tag.equals("ruby")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                flushRubyGroup(bases, rts, outBuf, style, out);
                appendRuby(el, outBuf, style, out);
                continue;
            }
            // Any other inline wrapper inside <ruby> (<span>, <b>, <em>, <a>, etc.)
            String inlineText = normRubyPart(plainTextWithoutRuby(el));
            if (!inlineText.isEmpty()) {
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, out);
                looseBase.append(inlineText);
            }
        }
        if (looseBase.length() > 0) {
            bases.add(looseBase.toString());
        }
    }

    private static void flushRubyGroup(List<String> bases, List<String> rts, InlineBuf outBuf,
                                       int style, List<EpubBook.Block> out) {
        if (bases.isEmpty() && rts.isEmpty()) return;
        if (bases.isEmpty()) {
            // Malformed <ruby><rt>orphan</rt></ruby>: keep the reading as plain text.
            for (String rt : rts) {
                if (!rt.isEmpty()) appendText(outBuf, rt, style, out);
            }
            rts.clear();
            return;
        }
        if (rts.isEmpty()) {
            for (String base : bases) appendText(outBuf, base, style, out);
            bases.clear();
            return;
        }
        if (bases.size() == rts.size()) {
            for (int i = 0; i < bases.size(); i++) {
                emitSingleRuby(bases.get(i), rts.get(i), outBuf, style, out);
            }
        } else {
            StringBuilder joinedBase = new StringBuilder();
            for (String b : bases) joinedBase.append(b);
            StringBuilder joinedRt = new StringBuilder();
            for (String r : rts) joinedRt.append(r);
            emitSingleRuby(joinedBase.toString(), joinedRt.toString(), outBuf, style, out);
        }
        bases.clear();
        rts.clear();
    }

    private static void emitSingleRuby(String base, String rt, InlineBuf outBuf, int style,
                                       List<EpubBook.Block> out) {
        String cleanBase = norm(base);
        String cleanRt = norm(rt);
        if (cleanBase.isEmpty()) {
            if (!cleanRt.isEmpty()) appendText(outBuf, cleanRt, style, out);
            return;
        }
        joinEastAsianLineBreakIfNeeded(outBuf, Character.codePointAt(cleanBase, 0));
        int start = outBuf.sb.length();
        outBuf.sb.append(cleanBase);
        outBuf.trailingLineBreakSpace = false;
        int end = outBuf.sb.length();
        if (!cleanRt.isEmpty() && start < end) {
            outBuf.rubies.add(new EpubBook.Ruby(start, end, cleanRt));
        }
    }

    private static String normRubyPart(String raw) {
        return raw.replace('\u00A0', ' ').replace('\u200B', ' ')
                .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    private static String plainTextWithoutRuby(Element el) {
        StringBuilder sb = new StringBuilder();
        collectPlainWithoutRuby(el, sb);
        return norm(sb.toString());
    }

    private static void collectPlainWithoutRuby(Element parent, StringBuilder sb) {
        for (Node n : parent.childNodes()) {
            if (n instanceof TextNode) {
                sb.append(((TextNode) n).getWholeText());
            } else if (n instanceof Element) {
                Element e = (Element) n;
                String tag = e.normalName();
                if (isSkippedTag(tag) || isHidden(e) || "rt".equals(tag) || "rtc".equals(tag)) {
                    continue;
                }
                collectPlainWithoutRuby(e, sb);
            }
        }
    }

    private static void joinEastAsianLineBreakIfNeeded(InlineBuf buf, int nextCodePoint) {
        if (buf.trailingLineBreakSpace && buf.sb.length() >= 2
                && buf.sb.charAt(buf.sb.length() - 1) == ' ') {
            int prevCp = Character.codePointBefore(buf.sb, buf.sb.length() - 1);
            if (isEastAsian(prevCp) && isEastAsian(nextCodePoint)) {
                buf.sb.setLength(buf.sb.length() - 1);
            }
        }
        buf.trailingLineBreakSpace = false;
    }

    /**
     * Folds source whitespace while respecting East Asian scripts: a line break before a
     * fullwidth paragraph indent starts a new paragraph, and a line break between two East
     * Asian characters joins directly instead of inserting an ASCII space mid-word.
     */
    private static void appendText(InlineBuf buf, String raw, int style,
                                   List<EpubBook.Block> out) {
        StringBuilder sb = buf.sb;
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\u00A0' || c == '\u200B') c = ' ';
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t') {
                boolean sawLineBreak = buf.trailingLineBreakSpace;
                int j = i;
                while (j < n) {
                    char w = raw.charAt(j);
                    if (w == '\r' || w == '\n') {
                        sawLineBreak = true;
                        j++;
                    } else if (w == ' ' || w == '\t' || w == '\u00A0' || w == '\u200B') {
                        j++;
                    } else {
                        break;
                    }
                }
                if (sawLineBreak && j < n && raw.charAt(j) == '\u3000') {
                    buf.flush(style, out);
                    i = j;
                    continue;
                }
                int prevCp = sb.length() > 0 ? lastNonSpaceCodePoint(sb, buf.trailingLineBreakSpace) : 0;
                int nextCp = j < n ? Character.codePointAt(raw, j) : 0;
                if (sawLineBreak && prevCp != 0 && nextCp != 0
                        && isEastAsian(prevCp) && isEastAsian(nextCp)) {
                    if (buf.trailingLineBreakSpace && sb.length() > 0
                            && sb.charAt(sb.length() - 1) == ' ') {
                        sb.setLength(sb.length() - 1);
                    }
                    buf.trailingLineBreakSpace = false;
                    i = j;
                    continue;
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                buf.trailingLineBreakSpace = (j == n) && sawLineBreak;
                i = j;
                continue;
            }
            int cp = Character.codePointAt(raw, i);
            joinEastAsianLineBreakIfNeeded(buf, cp);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
    }

    private static int lastNonSpaceCodePoint(StringBuilder sb, boolean trailingSpaceIsLineBreak) {
        int end = sb.length();
        if (trailingSpaceIsLineBreak && end >= 2 && sb.charAt(end - 1) == ' ') {
            return Character.codePointBefore(sb, end - 1);
        }
        return Character.codePointBefore(sb, end);
    }

    static boolean isEastAsian(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFFEF)
                || (cp >= 0x20000 && cp <= 0x323AF);
    }

    static boolean isEastAsian(char c) {
        return isEastAsian((int) c);
    }

    private static boolean isSkippedTag(String tag) {
        switch (tag) {
            case "script":
            case "style":
            case "head":
            case "title":
            case "noscript":
            case "rp":
                return true;
            default:
                return false;
        }
    }

    private static boolean isBlockContainer(String tag) {
        switch (tag) {
            case "div":
            case "section":
            case "article":
            case "aside":
            case "header":
            case "footer":
            case "main":
            case "ul":
            case "ol":
            case "dl":
            case "table":
            case "thead":
            case "tbody":
            case "tfoot":
            case "tr":
            case "td":
            case "th":
            case "caption":
            case "center":
            case "nav":
            case "hr":
                return true;
            default:
                return false;
        }
    }

    private static boolean isTextBlock(String tag) {
        switch (tag) {
            case "p":
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "li":
            case "dd":
            case "dt":
            case "figcaption":
            case "pre":
                return true;
            default:
                return false;
        }
    }

    private static int styleFor(String tag) {
        switch (tag) {
            case "h1":
                return EpubBook.Block.STYLE_H1;
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "dt":
                return EpubBook.Block.STYLE_H2;
            case "li":
                return EpubBook.Block.STYLE_LIST;
            default:
                return EpubBook.Block.STYLE_BODY;
        }
    }

    private static boolean isImageEl(Element el) {
        String tag = el.normalName();
        return tag.equals("img") || tag.equals("image");
    }

    private static boolean hasImages(Element el) {
        return !el.select("img,image").isEmpty();
    }

    private static boolean isHidden(Element el) {
        String style = el.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
        if (style.contains("display:none") || style.contains("visibility:hidden")) return true;
        return !el.attr("hidden").isEmpty();
    }

    private void addImage(Element el, String baseDir, List<EpubBook.Block> out) {
        String src = el.attr("src");
        if (src.isEmpty()) src = el.attr("xlink:href");
        if (src.isEmpty()) src = el.attr("href");
        if (src.isEmpty() || src.startsWith("data:")) return;
        String path = normalize(baseDir + src);
        try (java.io.InputStream in = zip.open(path)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return;
            out.add(EpubBook.Block.image(path, opts.outWidth, opts.outHeight));
        } catch (Exception ignored) {
        }
    }

    private static void assignOffsets(EpubBook.Chapter chapter) {
        int offset = 0;
        for (EpubBook.Block b : chapter.blocks) {
            b.charStart = offset;
            offset += b.type == EpubBook.Block.TEXT ? b.text.length() + 1 : 1;
        }
        chapter.charCount = Math.max(offset, 1);
    }

    // ------------------------------------------------------------------ utils

    static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /** Resolves "." and ".." segments; the result uses "/" separators, no leading "./". */
    static String normalize(String path) {
        String p = path.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(segment);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    static String norm(String text) {
        if (text == null) return "";
        return text.replace('\u00A0', ' ')
                .replace('\u200B', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
