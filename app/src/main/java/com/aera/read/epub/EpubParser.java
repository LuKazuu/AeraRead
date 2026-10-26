package com.aera.read.epub;

import android.graphics.BitmapFactory;
import android.util.Base64;

import com.aera.read.io.ZipArchive;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EPUB 2/3 parser. Flattens each spine document into styled text blocks with exact character
 * offsets, inline formatting spans, ruby annotations, inline images/gaiji, and standalone
 * illustrations in a single DOM pass.
 */
public final class EpubParser {

    static final char OBJECT_REPLACEMENT = '\uFFFC';
    private static final int LARGE_BLOCK_IMAGE_PX = 420;

    private final ZipArchive zip;
    private Map<String, String[]> items = new HashMap<>();
    private final Map<String, EpubCss> sheetCache = new HashMap<>();
    private final Map<String, int[]> boundsCache = new HashMap<>();

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
            String baseDir = dirOf(path);
            EpubCss css = loadChapterCss(doc, baseDir);
            EpubBook.Chapter chapter = new EpubBook.Chapter();
            chapter.index = book.chapters.size();
            chapter.path = path;
            if (doc.body() != null) walk(doc.body(), baseDir, css, chapter.blocks);
            assignOffsets(chapter);
            book.chapters.add(chapter);
        }
        if (book.chapters.isEmpty()) throw new IOException("No readable chapters");

        book.toc.addAll(parseNav(book));
        if (book.toc.isEmpty()) book.toc.addAll(parseNcx(book));

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

    private EpubCss loadChapterCss(Document doc, String baseDir) {
        EpubCss combined = null;
        for (Element el : doc.select("link,style")) {
            String tag = el.normalName();
            if ("link".equals(tag)) {
                String rel = el.attr("rel").toLowerCase(Locale.ROOT);
                String type = el.attr("type").toLowerCase(Locale.ROOT);
                String href = el.attr("href").trim();
                if (href.isEmpty()) continue;
                if (!rel.contains("stylesheet") && !type.contains("css")
                        && !href.toLowerCase(Locale.ROOT).endsWith(".css")) {
                    continue;
                }
                int hash = href.indexOf('#');
                if (hash >= 0) href = href.substring(0, hash);
                String cssPath = normalize(baseDir + href);
                EpubCss cached = sheetCache.get(cssPath);
                if (cached == null) {
                    cached = EpubCss.EMPTY;
                    try {
                        byte[] raw = zip.read(cssPath);
                        if (raw != null) {
                            cached = EpubCss.parseSheet(new String(raw, StandardCharsets.UTF_8));
                        }
                    } catch (Exception ignored) {
                    }
                    sheetCache.put(cssPath, cached);
                }
                if (!cached.isEmpty()) {
                    if (combined == null) combined = new EpubCss();
                    combined.addSheet(cached);
                }
            } else if ("style".equals(tag)) {
                EpubCss inl = EpubCss.parseSheet(el.data());
                if (!inl.isEmpty()) {
                    if (combined == null) combined = new EpubCss();
                    combined.addSheet(inl);
                }
            }
        }
        return combined == null ? EpubCss.EMPTY : combined;
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
                    && (b.style == EpubBook.Block.STYLE_H1 || b.style == EpubBook.Block.STYLE_H2)) {
                String clean = stripPlaceholders(b.text);
                if (!clean.isEmpty()) return clean;
            }
        }
        return "";
    }

    private static String stripPlaceholders(String s) {
        if (s == null || s.indexOf(OBJECT_REPLACEMENT) < 0) return s == null ? "" : s;
        return norm(s.replace(OBJECT_REPLACEMENT, ' '));
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
        final List<EpubBook.InlineSpan> spans = new ArrayList<>();
        final List<EpubBook.InlineImage> images = new ArrayList<>();
        boolean trailingLineBreakSpace;
        boolean lastPlaceholderAfterEastAsian;

        void recordSpan(int start, int end, int mask) {
            if (mask == 0 || start >= end) return;
            int n = spans.size();
            if (n > 0) {
                EpubBook.InlineSpan last = spans.get(n - 1);
                if (last.end == start && last.mask == mask) {
                    spans.set(n - 1, new EpubBook.InlineSpan(last.start, end, mask));
                    return;
                }
            }
            spans.add(new EpubBook.InlineSpan(start, end, mask));
        }

        void flush(int style, List<EpubBook.Block> out) {
            int n = sb.length();
            int start = 0;
            int end = n;
            while (start < end && sb.charAt(start) <= ' ') start++;
            while (end > start && sb.charAt(end - 1) <= ' ') end--;
            if (start < end) {
                if (!images.isEmpty() && !hasNonImageText(sb, start, end)) {
                    // Paragraph contains ONLY image(s) with no surrounding text -> standalone
                    // block illustration(s) so figures/covers render full-width & zoomable.
                    for (EpubBook.InlineImage img : images) {
                        if (img.pos >= start && img.pos < end) {
                            out.add(EpubBook.Block.image(img.path, img.imgW, img.imgH));
                        }
                    }
                } else {
                    String text = sb.substring(start, end);
                    int len = text.length();

                    List<EpubBook.Ruby> shiftedRubies = null;
                    if (!rubies.isEmpty()) {
                        shiftedRubies = new ArrayList<>(rubies.size());
                        for (EpubBook.Ruby r : rubies) {
                            int rs = Math.max(0, r.start - start);
                            int re = Math.min(len, r.end - start);
                            if (rs < re && !r.text.isEmpty()) {
                                shiftedRubies.add(new EpubBook.Ruby(rs, re, r.text));
                            }
                        }
                    }

                    List<EpubBook.InlineSpan> shiftedSpans = null;
                    if (!spans.isEmpty()) {
                        shiftedSpans = new ArrayList<>(spans.size());
                        for (EpubBook.InlineSpan s : spans) {
                            int ss = Math.max(0, s.start - start);
                            int se = Math.min(len, s.end - start);
                            if (ss < se && s.mask != 0) {
                                shiftedSpans.add(new EpubBook.InlineSpan(ss, se, s.mask));
                            }
                        }
                    }

                    List<EpubBook.InlineImage> shiftedImages = null;
                    if (!images.isEmpty()) {
                        shiftedImages = new ArrayList<>(images.size());
                        for (EpubBook.InlineImage img : images) {
                            int p = img.pos - start;
                            if (p >= 0 && p < len && text.charAt(p) == OBJECT_REPLACEMENT) {
                                shiftedImages.add(new EpubBook.InlineImage(
                                        p, img.path, img.imgW, img.imgH,
                                        img.emW, img.emH, img.pxW, img.pxH));
                            }
                        }
                    }

                    out.add(EpubBook.Block.richText(
                            text, style, shiftedRubies, shiftedSpans, shiftedImages));
                }
            }
            sb.setLength(0);
            rubies.clear();
            spans.clear();
            images.clear();
            trailingLineBreakSpace = false;
            lastPlaceholderAfterEastAsian = false;
        }

        private static boolean hasNonImageText(StringBuilder sb, int start, int end) {
            for (int i = start; i < end; i++) {
                char c = sb.charAt(i);
                if (c > ' ' && c != OBJECT_REPLACEMENT) return true;
            }
            return false;
        }
    }

    private void walk(Element parent, String baseDir, EpubCss css, List<EpubBook.Block> out) {
        InlineBuf inline = new InlineBuf();
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode) {
                appendText(inline, ((TextNode) node).getWholeText(),
                        EpubBook.Block.STYLE_BODY, 0, out);
                continue;
            }
            if (!(node instanceof Element)) continue;
            Element child = (Element) node;
            String tag = child.normalName();
            if (isSkippedTag(tag)) continue;
            EpubCss.Decl decl = css.resolve(child);
            if (isHidden(child, decl)) continue;

            if (tag.equals("br")) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                continue;
            }
            if (isImageEl(child)) {
                appendImage(child, decl, baseDir, inline, EpubBook.Block.STYLE_BODY, 0, out);
                continue;
            }
            if (tag.equals("svg")) {
                Element svgImg = singleSvgImage(child);
                if (svgImg != null) {
                    appendImage(svgImg, decl, baseDir, inline, EpubBook.Block.STYLE_BODY, 0, out);
                } else {
                    inline.flush(EpubBook.Block.STYLE_BODY, out);
                    walk(child, baseDir, css, out);
                }
                continue;
            }
            if (isTextBlock(tag)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                int bStyle = styleFor(tag);
                int initMask = tagInlineMask(tag);
                initMask = mergeInlineMask(initMask, decl);
                emitTextBlocks(child, baseDir, css, bStyle, initMask, out);
                continue;
            }
            if (tag.equals("blockquote") || tag.equals("figure")) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                if (hasNestedBlocks(child)) {
                    walk(child, baseDir, css, out);
                } else {
                    int initMask = mergeInlineMask(0, decl);
                    emitTextBlocks(child, baseDir, css, EpubBook.Block.STYLE_QUOTE, initMask, out);
                }
                continue;
            }
            if (isBlockContainer(tag) || (decl != null && decl.display == EpubCss.DISPLAY_BLOCK)) {
                inline.flush(EpubBook.Block.STYLE_BODY, out);
                walk(child, baseDir, css, out);
                continue;
            }
            int childMask = mergeInlineMask(tagInlineMask(tag), decl);
            collectInline(child, baseDir, css, inline, EpubBook.Block.STYLE_BODY, childMask, out);
        }
        inline.flush(EpubBook.Block.STYLE_BODY, out);
    }

    private static Element singleSvgImage(Element svg) {
        Element found = null;
        for (Element el : svg.getAllElements()) {
            if (el == svg) continue;
            String n = el.normalName();
            if ("image".equals(n) || "img".equals(n)) {
                if (found != null) return null;
                found = el;
            } else if ("text".equals(n)) {
                return null;
            }
        }
        return found;
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

    private void emitTextBlocks(Element el, String baseDir, EpubCss css, int style,
                                int initialMask, List<EpubBook.Block> out) {
        InlineBuf buf = new InlineBuf();
        collectInline(el, baseDir, css, buf, style, initialMask, out);
        buf.flush(style, out);
    }

    private void collectInline(Element parent, String baseDir, EpubCss css, InlineBuf buf,
                               int style, int activeMask, List<EpubBook.Block> out) {
        if ("ruby".equals(parent.normalName())) {
            appendRuby(parent, baseDir, css, buf, style, activeMask, out);
            return;
        }
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode) {
                appendText(buf, ((TextNode) node).getWholeText(), style, activeMask, out);
            } else if (node instanceof Element) {
                Element child = (Element) node;
                String tag = child.normalName();
                if (isSkippedTag(tag)) continue;
                EpubCss.Decl decl = css.resolve(child);
                if (isHidden(child, decl)) continue;

                if (tag.equals("br")) {
                    buf.flush(style, out);
                } else if (isImageEl(child)) {
                    appendImage(child, decl, baseDir, buf, style, activeMask, out);
                } else if (tag.equals("svg")) {
                    Element svgImg = singleSvgImage(child);
                    if (svgImg != null) {
                        appendImage(svgImg, decl, baseDir, buf, style, activeMask, out);
                    }
                } else if (tag.equals("ruby")) {
                    int rubyMask = mergeInlineMask(activeMask, decl);
                    appendRuby(child, baseDir, css, buf, style, rubyMask, out);
                } else {
                    if (decl != null && decl.display == EpubCss.DISPLAY_BLOCK) {
                        buf.flush(style, out);
                        int nextMask = mergeInlineMask(
                                applyTagMask(activeMask, tag), decl);
                        collectInline(child, baseDir, css, buf, style, nextMask, out);
                        buf.flush(style, out);
                    } else {
                        int nextMask = mergeInlineMask(
                                applyTagMask(activeMask, tag), decl);
                        collectInline(child, baseDir, css, buf, style, nextMask, out);
                    }
                }
            }
        }
    }

    private static int tagInlineMask(String tag) {
        return applyTagMask(0, tag);
    }

    private static int applyTagMask(int mask, String tag) {
        switch (tag) {
            case "b":
            case "strong":
                return (mask & ~EpubBook.InlineSpan.CLEAR_BOLD) | EpubBook.InlineSpan.BOLD;
            case "i":
            case "em":
            case "cite":
            case "dfn":
            case "var":
                return (mask & ~EpubBook.InlineSpan.CLEAR_ITALIC) | EpubBook.InlineSpan.ITALIC;
            case "u":
            case "ins":
                return mask | EpubBook.InlineSpan.UNDERLINE;
            case "s":
            case "del":
            case "strike":
                return mask | EpubBook.InlineSpan.STRIKE;
            case "code":
            case "kbd":
            case "samp":
            case "tt":
            case "pre":
                return mask | EpubBook.InlineSpan.CODE;
            case "sup":
                return (mask & ~EpubBook.InlineSpan.SUB) | EpubBook.InlineSpan.SUP;
            case "sub":
                return (mask & ~EpubBook.InlineSpan.SUP) | EpubBook.InlineSpan.SUB;
            case "small":
                return mask | EpubBook.InlineSpan.SMALL;
            default:
                return mask;
        }
    }

    private static int mergeInlineMask(int mask, EpubCss.Decl decl) {
        if (decl == null) return mask;
        int setFlags = mask & 0xFF;
        int clearFlags = mask & 0x300;
        if ((decl.clearMask & EpubBook.InlineSpan.CLEAR_BOLD) != 0) {
            setFlags &= ~EpubBook.InlineSpan.BOLD;
            clearFlags |= EpubBook.InlineSpan.CLEAR_BOLD;
        }
        if ((decl.clearMask & EpubBook.InlineSpan.CLEAR_ITALIC) != 0) {
            setFlags &= ~EpubBook.InlineSpan.ITALIC;
            clearFlags |= EpubBook.InlineSpan.CLEAR_ITALIC;
        }
        if ((decl.setMask & EpubBook.InlineSpan.BOLD) != 0) {
            clearFlags &= ~EpubBook.InlineSpan.CLEAR_BOLD;
        }
        if ((decl.setMask & EpubBook.InlineSpan.ITALIC) != 0) {
            clearFlags &= ~EpubBook.InlineSpan.CLEAR_ITALIC;
        }
        if ((decl.setMask & EpubBook.InlineSpan.SUP) != 0) {
            setFlags &= ~EpubBook.InlineSpan.SUB;
        } else if ((decl.setMask & EpubBook.InlineSpan.SUB) != 0) {
            setFlags &= ~EpubBook.InlineSpan.SUP;
        }
        setFlags |= decl.setMask;
        return setFlags | clearFlags;
    }

    private void appendImage(Element el, EpubCss.Decl decl, String baseDir, InlineBuf buf,
                             int style, int activeMask, List<EpubBook.Block> out) {
        String src = el.attr("src").trim();
        if (src.isEmpty()) src = el.attr("xlink:href").trim();
        if (src.isEmpty()) src = el.attr("href").trim();
        if (src.isEmpty()) return;

        String resolved = src.startsWith("data:") ? src : normalize(baseDir + src);
        int[] dims = imageBounds(resolved);
        if (dims == null || dims[0] <= 0 || dims[1] <= 0) {
            String alt = norm(el.attr("alt"));
            if (!alt.isEmpty()) appendText(buf, alt, style, activeMask, out);
            return;
        }

        float emW = decl != null ? decl.emW : 0f;
        float emH = decl != null ? decl.emH : 0f;
        float pxW = decl != null ? decl.pxW : 0f;
        float pxH = decl != null ? decl.pxH : 0f;
        if (emW == 0f && pxW == 0f) pxW = EpubCss.parseHtmlPxAttr(el.attr("width"));
        if (emH == 0f && pxH == 0f) pxH = EpubCss.parseHtmlPxAttr(el.attr("height"));

        boolean explicitInlineSize = emW > 0f || emH > 0f || pxW > 0f || pxH > 0f;
        boolean forceBlock = (decl != null && decl.display == EpubCss.DISPLAY_BLOCK)
                || (!explicitInlineSize
                && (decl == null || decl.display != EpubCss.DISPLAY_INLINE)
                && dims[0] >= LARGE_BLOCK_IMAGE_PX && dims[1] >= LARGE_BLOCK_IMAGE_PX);

        if (forceBlock) {
            buf.flush(style, out);
            out.add(EpubBook.Block.image(resolved, dims[0], dims[1]));
            return;
        }

        // Inline image / gaiji: join pretty-printed line breaks adjacent to East Asian characters
        boolean prevEastAsian = false;
        if (buf.sb.length() > 0) {
            int prevCp = lastNonSpaceCodePoint(buf.sb, buf.trailingLineBreakSpace);
            if (prevCp != 0 && isEastAsian(prevCp)) {
                prevEastAsian = true;
                if (buf.trailingLineBreakSpace && buf.sb.charAt(buf.sb.length() - 1) == ' ') {
                    buf.sb.setLength(buf.sb.length() - 1);
                }
            }
        }
        buf.trailingLineBreakSpace = false;
        buf.lastPlaceholderAfterEastAsian = prevEastAsian;

        int pos = buf.sb.length();
        buf.sb.append(OBJECT_REPLACEMENT);
        buf.images.add(new EpubBook.InlineImage(
                pos, resolved, dims[0], dims[1], emW, emH, pxW, pxH));
    }

    private int[] imageBounds(String path) {
        int[] cached = boundsCache.get(path);
        if (cached != null) return cached;
        int[] decoded = ImageStore.readBounds(zip, path);
        boundsCache.put(path, decoded == null ? new int[]{0, 0} : decoded);
        return decoded;
    }

    /**
     * Parses HTML5 and W3C XHTML {@code <ruby>} annotations ({@code <rb>}, {@code <rbc>},
     * {@code <rt>}, {@code <rtc>}, {@code <rp>}), preserving inline formatting on the ruby base.
     */
    private void appendRuby(Element rubyEl, String baseDir, EpubCss css, InlineBuf buf,
                            int style, int activeMask, List<EpubBook.Block> out) {
        List<String> bases = new ArrayList<>(2);
        List<String> rts = new ArrayList<>(2);
        collectRubyParts(rubyEl, baseDir, css, bases, rts, buf, style, activeMask, out);
        flushRubyGroup(bases, rts, buf, style, activeMask, out);
    }

    private void collectRubyParts(Element container, String baseDir, EpubCss css,
                                  List<String> bases, List<String> rts, InlineBuf outBuf,
                                  int style, int activeMask, List<EpubBook.Block> out) {
        StringBuilder looseBase = new StringBuilder();
        for (Node node : container.childNodes()) {
            if (node instanceof TextNode) {
                String chunk = normRubyPart(((TextNode) node).getWholeText());
                if (!chunk.isEmpty()) {
                    if (!rts.isEmpty()) {
                        flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
                    }
                    looseBase.append(chunk);
                }
                continue;
            }
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            String tag = el.normalName();
            if (isSkippedTag(tag)) continue;
            EpubCss.Decl decl = css.resolve(el);
            if (isHidden(el, decl) || isImageEl(el)) continue;

            if (tag.equals("br")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
                outBuf.flush(style, out);
                continue;
            }
            if (tag.equals("rbc")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
                collectRubyParts(el, baseDir, css, bases, rts, outBuf, style, activeMask, out);
                continue;
            }
            if (tag.equals("rb")) {
                if (looseBase.length() > 0) {
                    bases.add(looseBase.toString());
                    looseBase.setLength(0);
                }
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
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
                        if (isSkippedTag(re.normalName())
                                || isHidden(re, css.resolve(re))) {
                            continue;
                        }
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
                flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
                appendRuby(el, baseDir, css, outBuf, style, activeMask, out);
                continue;
            }
            String inlineText = normRubyPart(plainTextWithoutRuby(el));
            if (!inlineText.isEmpty()) {
                if (!rts.isEmpty()) flushRubyGroup(bases, rts, outBuf, style, activeMask, out);
                looseBase.append(inlineText);
            }
        }
        if (looseBase.length() > 0) {
            bases.add(looseBase.toString());
        }
    }

    private static void flushRubyGroup(List<String> bases, List<String> rts, InlineBuf outBuf,
                                       int style, int activeMask, List<EpubBook.Block> out) {
        if (bases.isEmpty() && rts.isEmpty()) return;
        if (bases.isEmpty()) {
            for (String rt : rts) {
                if (!rt.isEmpty()) appendText(outBuf, rt, style, activeMask, out);
            }
            rts.clear();
            return;
        }
        if (rts.isEmpty()) {
            for (String base : bases) appendText(outBuf, base, style, activeMask, out);
            bases.clear();
            return;
        }
        if (bases.size() == rts.size()) {
            for (int i = 0; i < bases.size(); i++) {
                emitSingleRuby(bases.get(i), rts.get(i), outBuf, style, activeMask, out);
            }
        } else {
            StringBuilder joinedBase = new StringBuilder();
            for (String b : bases) joinedBase.append(b);
            StringBuilder joinedRt = new StringBuilder();
            for (String r : rts) joinedRt.append(r);
            emitSingleRuby(joinedBase.toString(), joinedRt.toString(),
                    outBuf, style, activeMask, out);
        }
        bases.clear();
        rts.clear();
    }

    private static void emitSingleRuby(String base, String rt, InlineBuf outBuf, int style,
                                       int activeMask, List<EpubBook.Block> out) {
        String cleanBase = norm(base);
        String cleanRt = norm(rt);
        if (cleanBase.isEmpty()) {
            if (!cleanRt.isEmpty()) appendText(outBuf, cleanRt, style, activeMask, out);
            return;
        }
        joinEastAsianLineBreakIfNeeded(outBuf, Character.codePointAt(cleanBase, 0));
        int start = outBuf.sb.length();
        outBuf.sb.append(cleanBase);
        outBuf.trailingLineBreakSpace = false;
        outBuf.lastPlaceholderAfterEastAsian = false;
        int end = outBuf.sb.length();
        outBuf.recordSpan(start, end, activeMask);
        if (!cleanRt.isEmpty() && start < end) {
            outBuf.rubies.add(new EpubBook.Ruby(start, end, cleanRt));
        }
    }

    private static String normRubyPart(String raw) {
        return norm(raw);
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
                if (isSkippedTag(tag) || isHidden(e, null)
                        || "rt".equals(tag) || "rtc".equals(tag)) {
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
            boolean prevEast = isEastAsian(prevCp)
                    || (prevCp == OBJECT_REPLACEMENT && buf.lastPlaceholderAfterEastAsian);
            if (prevEast && isEastAsian(nextCodePoint)) {
                buf.sb.setLength(buf.sb.length() - 1);
            }
        }
        buf.trailingLineBreakSpace = false;
    }

    private static void appendText(InlineBuf buf, String raw, int style, int activeMask,
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
                int prevCp = sb.length() > 0
                        ? lastNonSpaceCodePoint(sb, buf.trailingLineBreakSpace) : 0;
                int nextCp = j < n ? Character.codePointAt(raw, j) : 0;
                boolean prevEast = prevCp != 0 && (isEastAsian(prevCp)
                        || (prevCp == OBJECT_REPLACEMENT && buf.lastPlaceholderAfterEastAsian));
                if (sawLineBreak && prevEast && nextCp != 0 && isEastAsian(nextCp)) {
                    if (buf.trailingLineBreakSpace && sb.length() > 0
                            && sb.charAt(sb.length() - 1) == ' ') {
                        sb.setLength(sb.length() - 1);
                    }
                    buf.trailingLineBreakSpace = false;
                    i = j;
                    continue;
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    int spPos = sb.length();
                    sb.append(' ');
                    buf.recordSpan(spPos, spPos + 1, activeMask);
                }
                buf.trailingLineBreakSpace = (j == n) && sawLineBreak;
                i = j;
                continue;
            }
            int cp = Character.codePointAt(raw, i);
            joinEastAsianLineBreakIfNeeded(buf, cp);
            int runStart = sb.length();
            sb.appendCodePoint(cp);
            buf.lastPlaceholderAfterEastAsian = false;
            buf.recordSpan(runStart, sb.length(), activeMask);
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

    private static boolean isHidden(Element el, EpubCss.Decl decl) {
        if (decl != null && decl.display == EpubCss.DISPLAY_NONE) return true;
        if (decl == null) {
            String style = el.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
            if (style.contains("display:none") || style.contains("visibility:hidden")) return true;
        }
        return el.hasAttr("hidden");
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
        int start = 0;
        int n = p.length();
        while (start <= n) {
            int slash = p.indexOf('/', start);
            if (slash < 0) slash = n;
            if (slash > start) {
                String segment = p.substring(start, slash);
                if (!segment.equals(".")) {
                    if (segment.equals("..")) {
                        if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                    } else {
                        parts.add(segment);
                    }
                }
            }
            start = slash + 1;
        }
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Single-pass whitespace normalizer (zero regex compilation / allocation overhead). */
    static String norm(String text) {
        if (text == null || text.isEmpty()) return "";
        int n = text.length();
        StringBuilder sb = new StringBuilder(n);
        boolean inSpace = true;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c == '\u00A0' || c == '\u200B') {
                if (!inSpace) {
                    sb.append(' ');
                    inSpace = true;
                }
            } else {
                sb.append(c);
                inSpace = false;
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    static byte[] decodeDataUri(String uri) {
        if (uri == null || !uri.startsWith("data:")) return null;
        int comma = uri.indexOf(',');
        if (comma < 0) return null;
        String meta = uri.substring(5, comma).toLowerCase(Locale.ROOT);
        String payload = uri.substring(comma + 1);
        try {
            if (meta.contains(";base64")) {
                return Base64.decode(payload, Base64.DEFAULT);
            }
            return java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
