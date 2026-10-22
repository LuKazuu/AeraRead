package com.aera.read.util;

/**
 * Fonts the reader can download. Every entry is the original release from the Google Fonts
 * repository, fetched on demand; sizes are the upstream file sizes used as a label and as a
 * download total when the server does not send one.
 */
public final class FontCatalog {

    public static final String LATIN = "latin";
    public static final String CJK = "cjk";

    public static final class Entry {
        public final String id;
        public final String label;
        public final String group;
        public final String url;
        public final long bytes;

        Entry(String id, String label, String group, String path, long bytes) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.url = BASE + path;
            this.bytes = bytes;
        }

        public boolean isCjk() {
            return CJK.equals(group);
        }
    }

    private static final String BASE = "https://raw.githubusercontent.com/google/fonts/main/ofl/";

    public static final Entry[] ALL = {
            new Entry("literata", "Literata", LATIN,
                    "literata/Literata%5Bopsz,wght%5D.ttf", 955132),
            new Entry("inter", "Inter", LATIN,
                    "inter/Inter%5Bopsz,wght%5D.ttf", 876576),
            new Entry("lora", "Lora", LATIN,
                    "lora/Lora%5Bwght%5D.ttf", 212196),
            new Entry("opensans", "Open Sans", LATIN,
                    "opensans/OpenSans%5Bwdth,wght%5D.ttf", 532636),
            new Entry("sourceserif4", "Source Serif 4", LATIN,
                    "sourceserif4/SourceSerif4%5Bopsz,wght%5D.ttf", 1209508),
            new Entry("crimsonpro", "Crimson Pro", LATIN,
                    "crimsonpro/CrimsonPro%5Bwght%5D.ttf", 246324),
            new Entry("ebgaramond", "EB Garamond", LATIN,
                    "ebgaramond/EBGaramond%5Bwght%5D.ttf", 851176),
            new Entry("bitter", "Bitter", LATIN,
                    "bitter/Bitter%5Bwght%5D.ttf", 328636),
            new Entry("vollkorn", "Vollkorn", LATIN,
                    "vollkorn/Vollkorn%5Bwght%5D.ttf", 577864),
            new Entry("publicsans", "Public Sans", LATIN,
                    "publicsans/PublicSans%5Bwght%5D.ttf", 103316),
            new Entry("worksans", "Work Sans", LATIN,
                    "worksans/WorkSans%5Bwght%5D.ttf", 361072),
            new Entry("ibmplexserif", "IBM Plex Serif", LATIN,
                    "ibmplexserif/IBMPlexSerif-Regular.ttf", 163068),
            new Entry("newsreader", "Newsreader", LATIN,
                    "newsreader/Newsreader%5Bopsz,wght%5D.ttf", 451664),
            new Entry("petrona", "Petrona", LATIN,
                    "petrona/Petrona%5Bwght%5D.ttf", 218572),
            new Entry("merriweather", "Merriweather", LATIN,
                    "merriweather/Merriweather%5Bopsz,wdth,wght%5D.ttf", 4628080),

            new Entry("notosc", "Noto Sans SC", CJK,
                    "notosanssc/NotoSansSC%5Bwght%5D.ttf", 17772300),
            new Entry("nototc", "Noto Sans TC", CJK,
                    "notosanstc/NotoSansTC%5Bwght%5D.ttf", 11941968),
            new Entry("notohk", "Noto Sans HK", CJK,
                    "notosanshk/NotoSansHK%5Bwght%5D.ttf", 11905808),
            new Entry("notojp", "Noto Sans JP", CJK,
                    "notosansjp/NotoSansJP%5Bwght%5D.ttf", 9589900),
            new Entry("notokr", "Noto Sans KR", CJK,
                    "notosanskr/NotoSansKR%5Bwght%5D.ttf", 10414588),
            new Entry("notoserifsc", "Noto Serif SC", CJK,
                    "notoserifsc/NotoSerifSC%5Bwght%5D.ttf", 25125512),
            new Entry("notoserifjp", "Noto Serif JP", CJK,
                    "notoserifjp/NotoSerifJP%5Bwght%5D.ttf", 13574352),
    };

    private FontCatalog() {
    }

    public static Entry byId(String id) {
        if (id == null) return null;
        for (Entry entry : ALL) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    /** The name shown for a stored font key: system, a catalog face, or the key itself. */
    public static String label(String id) {
        if (id == null || Fonts.SYSTEM.equals(id)) return "System";
        Entry entry = byId(id);
        return entry == null ? id : entry.label;
    }

    /** Human size hint, e.g. "17.8 MB". */
    public static String sizeLabel(long bytes) {
        if (bytes >= 1000000L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6);
        return Math.max(1, bytes / 1000) + " KB";
    }
}
