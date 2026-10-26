package com.aera.read.epub;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight CSS rule & inline-declaration parser for EPUB 2/3 stylesheets.
 * Resolves inline typography (bold, italic, underline, strikethrough, monospace, sup/sub,
 * small/small-caps), visibility/display overrides, and explicit {@code em}/{@code px} sizing
 * on inline images/gaiji.
 */
final class EpubCss {

    static final EpubCss EMPTY = new EpubCss();

    static final int DISPLAY_DEFAULT = 0;
    static final int DISPLAY_NONE = 1;
    static final int DISPLAY_BLOCK = 2;
    static final int DISPLAY_INLINE = 3;

    static final class Decl {
        int setMask;
        int clearMask;
        int display;
        float emW, emH;
        float pxW, pxH;

        boolean isEmpty() {
            return setMask == 0 && clearMask == 0 && display == 0
                    && emW == 0f && emH == 0f && pxW == 0f && pxH == 0f;
        }

        void mergeFrom(Decl other) {
            if (other == null) return;
            // New declarations override opposing flags from lower-priority rules.
            setMask = (setMask & ~other.clearMask) | other.setMask;
            clearMask = (clearMask & ~other.setMask) | other.clearMask;
            // SUP and SUB are mutually exclusive.
            if ((other.setMask & EpubBook.InlineSpan.SUP) != 0) {
                setMask &= ~EpubBook.InlineSpan.SUB;
            } else if ((other.setMask & EpubBook.InlineSpan.SUB) != 0) {
                setMask &= ~EpubBook.InlineSpan.SUP;
            }
            if (other.display != DISPLAY_DEFAULT) display = other.display;
            if (other.emW > 0f) {
                emW = other.emW;
                pxW = 0f;
            } else if (other.pxW > 0f) {
                pxW = other.pxW;
                emW = 0f;
            }
            if (other.emH > 0f) {
                emH = other.emH;
                pxH = 0f;
            } else if (other.pxH > 0f) {
                pxH = other.pxH;
                emH = 0f;
            }
        }
    }

    private final Map<String, Decl> byTag = new HashMap<>();
    private final Map<String, Decl> byClass = new HashMap<>();
    private final Map<String, Decl> byTagClass = new HashMap<>();
    private final Map<String, Decl> byId = new HashMap<>();

    boolean isEmpty() {
        return byTag.isEmpty() && byClass.isEmpty() && byTagClass.isEmpty() && byId.isEmpty();
    }

    void addSheet(EpubCss other) {
        if (other == null || other.isEmpty()) return;
        mergeMap(byTag, other.byTag);
        mergeMap(byClass, other.byClass);
        mergeMap(byTagClass, other.byTagClass);
        mergeMap(byId, other.byId);
    }

    private static void mergeMap(Map<String, Decl> dst, Map<String, Decl> src) {
        for (Map.Entry<String, Decl> e : src.entrySet()) {
            Decl cur = dst.get(e.getKey());
            if (cur == null) {
                cur = new Decl();
                dst.put(e.getKey(), cur);
            }
            cur.mergeFrom(e.getValue());
        }
    }

    static EpubCss parseSheet(String css) {
        if (css == null || css.isEmpty()) return EMPTY;
        EpubCss out = new EpubCss();
        out.parseRules(stripComments(css));
        return out.isEmpty() ? EMPTY : out;
    }

    private void parseRules(String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            while (i < n && s.charAt(i) <= ' ') i++;
            if (i >= n) break;
            if (s.charAt(i) == '@') {
                int brace = s.indexOf('{', i);
                int semi = s.indexOf(';', i);
                if (semi >= 0 && (brace < 0 || semi < brace)) {
                    i = semi + 1;
                    continue;
                }
                if (brace < 0) break;
                String header = s.substring(i, brace).toLowerCase(Locale.ROOT);
                int close = findMatchingBrace(s, brace);
                if (header.startsWith("@media") && !header.contains("print")) {
                    parseRules(s.substring(brace + 1, close));
                }
                i = close + 1;
                continue;
            }
            int brace = s.indexOf('{', i);
            if (brace < 0) break;
            String selectors = s.substring(i, brace).trim();
            int close = findMatchingBrace(s, brace);
            Decl decl = parseDeclarations(s.substring(brace + 1, close));
            if (!decl.isEmpty() && !selectors.isEmpty()) {
                for (String rawSel : splitChar(selectors, ',')) {
                    registerSelector(rawSel.trim(), decl);
                }
            }
            i = close + 1;
        }
    }

    private static int findMatchingBrace(String s, int openBrace) {
        int depth = 0;
        int n = s.length();
        for (int i = openBrace; i < n; i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return n;
    }

    private void registerSelector(String sel, Decl decl) {
        if (sel.isEmpty()) return;
        // Take the rightmost simple selector if descendant combinators are used (e.g. "p span.i")
        int lastSpace = Math.max(sel.lastIndexOf(' '), sel.lastIndexOf('>'));
        if (lastSpace >= 0) sel = sel.substring(lastSpace + 1).trim();
        int pseudo = sel.indexOf(':');
        if (pseudo >= 0) return; // ignore :hover, ::before, etc.
        if (sel.isEmpty() || sel.indexOf('[') >= 0 || sel.indexOf('+') >= 0
                || sel.indexOf('~') >= 0 || "*".equals(sel)) {
            return;
        }
        String lower = sel.toLowerCase(Locale.ROOT);
        int hash = lower.indexOf('#');
        if (hash >= 0) {
            String id = sel.substring(hash + 1).trim();
            int dot = id.indexOf('.');
            if (dot >= 0) id = id.substring(0, dot);
            if (!id.isEmpty()) putMerged(byId, id, decl);
            return;
        }
        int dot = sel.indexOf('.');
        if (dot == 0) {
            String cls = sel.substring(1).trim();
            if (!cls.isEmpty() && cls.indexOf('.') < 0) putMerged(byClass, cls, decl);
        } else if (dot > 0) {
            String tag = sel.substring(0, dot).trim().toLowerCase(Locale.ROOT);
            String cls = sel.substring(dot + 1).trim();
            if (!tag.isEmpty() && !cls.isEmpty() && cls.indexOf('.') < 0) {
                putMerged(byTagClass, tag + "." + cls, decl);
            }
        } else {
            putMerged(byTag, lower, decl);
        }
    }

    private static void putMerged(Map<String, Decl> map, String key, Decl decl) {
        Decl cur = map.get(key);
        if (cur == null) {
            cur = new Decl();
            map.put(key, cur);
        }
        cur.mergeFrom(decl);
    }

    /** Resolves combined CSS + inline {@code style="..."} declarations for an {@link Element}. */
    Decl resolve(Element el) {
        Decl out = null;
        String tag = el.normalName();
        if (!isEmpty()) {
            Decl t = byTag.get(tag);
            if (t != null) {
                out = new Decl();
                out.mergeFrom(t);
            }
            String clsAttr = el.attr("class");
            if (!clsAttr.isEmpty()) {
                for (String cls : splitWhitespace(clsAttr)) {
                    Decl c = byClass.get(cls);
                    if (c != null) {
                        if (out == null) out = new Decl();
                        out.mergeFrom(c);
                    }
                    Decl tc = byTagClass.get(tag + "." + cls);
                    if (tc != null) {
                        if (out == null) out = new Decl();
                        out.mergeFrom(tc);
                    }
                }
            }
            String id = el.attr("id");
            if (!id.isEmpty()) {
                Decl idDecl = byId.get(id);
                if (idDecl != null) {
                    if (out == null) out = new Decl();
                    out.mergeFrom(idDecl);
                }
            }
        }
        String inline = el.attr("style");
        if (!inline.isEmpty()) {
            Decl inl = parseDeclarations(inline);
            if (!inl.isEmpty()) {
                if (out == null) out = inl;
                else out.mergeFrom(inl);
            }
        }
        return out;
    }

    static Decl parseDeclarations(String raw) {
        Decl d = new Decl();
        if (raw == null || raw.isEmpty()) return d;
        int n = raw.length();
        int start = 0;
        while (start < n) {
            int semi = raw.indexOf(';', start);
            if (semi < 0) semi = n;
            int colon = raw.indexOf(':', start);
            if (colon > start && colon < semi) {
                String prop = raw.substring(start, colon).trim().toLowerCase(Locale.ROOT);
                String val = raw.substring(colon + 1, semi).trim().toLowerCase(Locale.ROOT);
                int bang = val.indexOf("!important");
                if (bang >= 0) val = val.substring(0, bang).trim();
                applyProperty(d, prop, val);
            }
            start = semi + 1;
        }
        return d;
    }

    private static void applyProperty(Decl d, String prop, String val) {
        if (val.isEmpty()) return;
        switch (prop) {
            case "display":
                if ("none".equals(val)) d.display = DISPLAY_NONE;
                else if ("block".equals(val)) d.display = DISPLAY_BLOCK;
                else if (val.startsWith("inline")) d.display = DISPLAY_INLINE;
                break;
            case "visibility":
                if ("hidden".equals(val) || "collapse".equals(val)) d.display = DISPLAY_NONE;
                break;
            case "font-weight":
                if ("bold".equals(val) || "bolder".equals(val) || parseWeight(val) >= 600) {
                    d.setMask |= EpubBook.InlineSpan.BOLD;
                    d.clearMask &= ~EpubBook.InlineSpan.CLEAR_BOLD;
                } else if ("normal".equals(val) || "lighter".equals(val)
                        || (parseWeight(val) >= 100 && parseWeight(val) < 600)) {
                    d.setMask &= ~EpubBook.InlineSpan.BOLD;
                    d.clearMask |= EpubBook.InlineSpan.CLEAR_BOLD;
                }
                break;
            case "font-style":
                if ("italic".equals(val) || "oblique".equals(val)) {
                    d.setMask |= EpubBook.InlineSpan.ITALIC;
                    d.clearMask &= ~EpubBook.InlineSpan.CLEAR_ITALIC;
                } else if ("normal".equals(val)) {
                    d.setMask &= ~EpubBook.InlineSpan.ITALIC;
                    d.clearMask |= EpubBook.InlineSpan.CLEAR_ITALIC;
                }
                break;
            case "text-decoration":
            case "text-decoration-line":
                if ("none".equals(val)) {
                    d.setMask &= ~(EpubBook.InlineSpan.UNDERLINE | EpubBook.InlineSpan.STRIKE);
                } else {
                    if (val.contains("underline")) d.setMask |= EpubBook.InlineSpan.UNDERLINE;
                    if (val.contains("line-through")) d.setMask |= EpubBook.InlineSpan.STRIKE;
                }
                break;
            case "font-family":
                if (val.contains("monospace") || val.contains("courier")
                        || val.contains("consolas") || val.contains("menlo")
                        || val.contains("source code") || val.contains("fira code")
                        || val.contains("jetbrains mono")) {
                    d.setMask |= EpubBook.InlineSpan.CODE;
                }
                break;
            case "vertical-align":
                if ("super".equals(val)) {
                    d.setMask = (d.setMask & ~EpubBook.InlineSpan.SUB) | EpubBook.InlineSpan.SUP;
                } else if ("sub".equals(val)) {
                    d.setMask = (d.setMask & ~EpubBook.InlineSpan.SUP) | EpubBook.InlineSpan.SUB;
                } else if ("baseline".equals(val)) {
                    d.setMask &= ~(EpubBook.InlineSpan.SUP | EpubBook.InlineSpan.SUB);
                }
                break;
            case "font-variant":
                if (val.contains("small-caps")) d.setMask |= EpubBook.InlineSpan.SMALL;
                break;
            case "font-size":
                if ("small".equals(val) || "smaller".equals(val)
                        || "x-small".equals(val) || "xx-small".equals(val)) {
                    d.setMask |= EpubBook.InlineSpan.SMALL;
                } else if (val.endsWith("em") || val.endsWith("rem")) {
                    float f = parseNumber(val);
                    if (f > 0f && f < 0.92f) d.setMask |= EpubBook.InlineSpan.SMALL;
                } else if (val.endsWith("%")) {
                    float f = parseNumber(val);
                    if (f > 0f && f < 92f) d.setMask |= EpubBook.InlineSpan.SMALL;
                }
                break;
            case "width":
                parseDimension(val, d, true);
                break;
            case "height":
                parseDimension(val, d, false);
                break;
            default:
                break;
        }
    }

    private static void parseDimension(String val, Decl d, boolean isWidth) {
        if ("auto".equals(val) || val.endsWith("%")) return;
        float num = parseNumber(val);
        if (num <= 0f) return;
        if (val.endsWith("em") || val.endsWith("rem")) {
            if (isWidth) d.emW = num;
            else d.emH = num;
        } else if (val.endsWith("ex") || val.endsWith("ch")) {
            if (isWidth) d.emW = num * 0.55f;
            else d.emH = num * 0.55f;
        } else if (val.endsWith("pt")) {
            if (isWidth) d.pxW = num * 1.333f;
            else d.pxH = num * 1.333f;
        } else {
            // px or unitless CSS number
            if (isWidth) d.pxW = num;
            else d.pxH = num;
        }
    }

    static float parseHtmlPxAttr(String attr) {
        if (attr == null || attr.isEmpty()) return 0f;
        String s = attr.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("%")) return 0f;
        return Math.max(0f, parseNumber(s));
    }

    private static int parseWeight(String val) {
        if (val.length() == 3 && val.charAt(0) >= '1' && val.charAt(0) <= '9'
                && val.charAt(1) == '0' && val.charAt(2) == '0') {
            return (val.charAt(0) - '0') * 100;
        }
        return -1;
    }

    private static float parseNumber(String s) {
        int end = 0;
        int n = s.length();
        boolean dot = false;
        while (end < n) {
            char c = s.charAt(end);
            if (c >= '0' && c <= '9') {
                end++;
            } else if (c == '.' && !dot) {
                dot = true;
                end++;
            } else {
                break;
            }
        }
        if (end == 0) return -1f;
        try {
            return Float.parseFloat(s.substring(0, end));
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static String stripComments(String s) {
        int idx = s.indexOf("/*");
        if (idx < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int pos = 0;
        while (idx >= 0) {
            sb.append(s, pos, idx);
            int close = s.indexOf("*/", idx + 2);
            if (close < 0) return sb.toString();
            pos = close + 2;
            idx = s.indexOf("/*", pos);
        }
        sb.append(s, pos, s.length());
        return sb.toString();
    }

    private static List<String> splitChar(String s, char sep) {
        int idx = s.indexOf(sep);
        if (idx < 0) return Collections.singletonList(s);
        List<String> out = new ArrayList<>(4);
        int start = 0;
        while (idx >= 0) {
            out.add(s.substring(start, idx));
            start = idx + 1;
            idx = s.indexOf(sep, start);
        }
        out.add(s.substring(start));
        return out;
    }

    private static List<String> splitWhitespace(String s) {
        int n = s.length();
        List<String> out = null;
        int i = 0;
        while (i < n) {
            while (i < n && s.charAt(i) <= ' ') i++;
            if (i >= n) break;
            int start = i;
            while (i < n && s.charAt(i) > ' ') i++;
            if (start == 0 && i == n) return Collections.singletonList(s);
            if (out == null) out = new ArrayList<>(3);
            out.add(s.substring(start, i));
        }
        return out == null ? Collections.emptyList() : out;
    }
}
