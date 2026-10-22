package com.aera.read.data;

import org.json.JSONException;
import org.json.JSONObject;

/** A document in the library. The book file always stays where the user keeps it. */
public class Book {
    public static final String TYPE_EPUB = "epub";
    public static final String TYPE_PDF = "pdf";

    public String uri = "";
    public String title = "";
    public String author = "";
    public String type = TYPE_EPUB;
    public long size;
    public long lastModified;
    public long addedAt;
    /** Small cached thumbnail produced during the folder scan (not a copy of the book). */
    public String coverFile = "";
    /** Last reading position: chapter index + character offset (+ fraction within the anchored page). */
    public int chapter = 0;
    public int offset = 0;
    public float fraction = 0f;
    public float progress = 0f;
    /** Typography + viewport the position was captured with; lets resume restore exactly. */
    public String layoutKey = "";

    public String id() {
        return uri;
    }

    public boolean isPdf() {
        return TYPE_PDF.equals(type);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("uri", uri);
            o.put("title", title);
            o.put("author", author);
            o.put("type", type);
            o.put("size", size);
            o.put("lastModified", lastModified);
            o.put("addedAt", addedAt);
            o.put("cover", coverFile);
            o.put("chapter", chapter);
            o.put("offset", offset);
            o.put("fraction", fraction);
            o.put("progress", progress);
            o.put("layout", layoutKey);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.uri = o.optString("uri");
        b.title = o.optString("title");
        b.author = o.optString("author");
        b.type = o.optString("type", TYPE_EPUB);
        b.size = o.optLong("size");
        b.lastModified = o.optLong("lastModified");
        b.addedAt = o.optLong("addedAt");
        b.coverFile = o.optString("cover");
        b.chapter = o.optInt("chapter");
        b.offset = o.optInt("offset");
        b.fraction = (float) o.optDouble("fraction", 0);
        b.progress = (float) o.optDouble("progress", 0);
        b.layoutKey = o.optString("layout");
        return b;
    }
}
