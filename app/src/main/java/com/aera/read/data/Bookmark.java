package com.aera.read.data;

import org.json.JSONException;
import org.json.JSONObject;

public class Bookmark {
    public String label = "";
    public int chapter = 0;
    public int offset = 0;
    public long created;

    public Bookmark() {
    }

    public Bookmark(String label, int chapter, int offset, long created) {
        this.label = label;
        this.chapter = chapter;
        this.offset = offset;
        this.created = created;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("label", label);
            o.put("chapter", chapter);
            o.put("offset", offset);
            o.put("created", created);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Bookmark fromJson(JSONObject o) {
        return new Bookmark(o.optString("label"), o.optInt("chapter"), o.optInt("offset"),
                o.optLong("created"));
    }
}
