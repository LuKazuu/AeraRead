package com.aera.read.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persistence for library folders, books, reading positions and bookmarks. */
public final class Library {

    private static final String PREFS = "aera_library";
    private static final String KEY_BOOKS = "books";
    private static final String KEY_FOLDERS = "folders";

    private static final Object LOCK = new Object();
    private static String cachedBooksRaw;
    private static List<Book> cachedBooks = Collections.emptyList();

    private static final class BookmarkCacheEntry {
        final String raw;
        final List<Bookmark> items;

        BookmarkCacheEntry(String raw, List<Bookmark> items) {
            this.raw = raw;
            this.items = items;
        }
    }

    private static final Map<String, BookmarkCacheEntry> BOOKMARK_CACHE = new HashMap<>();

    private Library() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- folders

    public static List<String> folders(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addFolder(Context c, String treeUri) {
        List<String> all = folders(c);
        if (!all.contains(treeUri)) {
            all.add(treeUri);
            saveFolders(c, all);
        }
    }

    public static void removeFolder(Context c, String treeUri) {
        if (treeUri == null) return;
        try {
            c.getContentResolver().releasePersistableUriPermission(
                    Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        List<String> all = folders(c);
        boolean changed = all.remove(treeUri);
        if (changed) saveFolders(c, all);
        if (all.isEmpty()) {
            clearBooks(c);
        } else {
            pruneBooksFromFolder(c, treeUri, all);
        }
    }

    public static void clearBooks(Context c) {
        List<Book> all = books(c);
        if (all.isEmpty()) return;
        SharedPreferences.Editor editor = sp(c).edit();
        synchronized (LOCK) {
            for (Book b : all) {
                deleteQuietly(b.coverFile);
                BOOKMARK_CACHE.remove(b.id());
                editor.remove("bm_" + b.id());
            }
        }
        editor.apply();
        persistBookList(c, Collections.emptyList());
    }

    private static void pruneBooksFromFolder(Context c, String removedFolder,
                                             List<String> remainingFolders) {
        List<Book> all = books(c);
        List<Book> kept = new ArrayList<>(all.size());
        SharedPreferences.Editor editor = null;
        for (Book b : all) {
            boolean inRemoved = belongsToFolder(b.uri, removedFolder);
            boolean inRemaining = false;
            if (inRemoved) {
                for (String rem : remainingFolders) {
                    if (belongsToFolder(b.uri, rem)) {
                        inRemaining = true;
                        break;
                    }
                }
            }
            if (inRemoved && !inRemaining) {
                deleteQuietly(b.coverFile);
                if (editor == null) editor = sp(c).edit();
                synchronized (LOCK) {
                    BOOKMARK_CACHE.remove(b.id());
                }
                editor.remove("bm_" + b.id());
            } else {
                kept.add(b);
            }
        }
        if (editor != null) {
            editor.apply();
            saveBooks(c, kept);
        }
    }

    public static boolean belongsToFolder(String bookUri, String folderTreeUri) {
        if (bookUri == null || folderTreeUri == null
                || bookUri.isEmpty() || folderTreeUri.isEmpty()) {
            return false;
        }
        if (bookUri.equals(folderTreeUri)
                || bookUri.startsWith(folderTreeUri.endsWith("/")
                        ? folderTreeUri : folderTreeUri + "/")) {
            return true;
        }
        try {
            Uri bUri = Uri.parse(bookUri);
            Uri fUri = Uri.parse(folderTreeUri);
            if (!Objects.equals(bUri.getScheme(), fUri.getScheme())
                    || !Objects.equals(bUri.getAuthority(), fUri.getAuthority())) {
                return false;
            }
            if (DocumentsContract.isTreeUri(bUri) && DocumentsContract.isTreeUri(fUri)) {
                return Objects.equals(
                        DocumentsContract.getTreeDocumentId(bUri),
                        DocumentsContract.getTreeDocumentId(fUri));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void saveFolders(Context c, List<String> folders) {
        JSONArray arr = new JSONArray();
        for (String f : folders) arr.put(f);
        sp(c).edit().putString(KEY_FOLDERS, arr.toString()).apply();
    }

    // ---------------------------------------------------------------- books

    private static List<Book> booksSnapshot(Context c) {
        String raw = sp(c).getString(KEY_BOOKS, "[]");
        synchronized (LOCK) {
            if (Objects.equals(raw, cachedBooksRaw)) return cachedBooks;
            List<Book> parsed = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    parsed.add(Book.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
            parsed.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
            cachedBooksRaw = raw;
            cachedBooks = Collections.unmodifiableList(parsed);
            return cachedBooks;
        }
    }

    private static void persistBookList(Context c, List<Book> ordered) {
        JSONArray arr = new JSONArray();
        for (Book b : ordered) arr.put(b.toJson());
        String raw = arr.toString();
        synchronized (LOCK) {
            cachedBooksRaw = raw;
            cachedBooks = Collections.unmodifiableList(ordered);
        }
        sp(c).edit().putString(KEY_BOOKS, raw).apply();
    }

    public static List<Book> books(Context c) {
        List<Book> snap = booksSnapshot(c);
        List<Book> out = new ArrayList<>(snap.size());
        for (Book b : snap) out.add(b.copy());
        return out;
    }

    public static void saveBooks(Context c, List<Book> books) {
        List<Book> sorted = new ArrayList<>(books.size());
        for (Book b : books) sorted.add(b.copy());
        sorted.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        persistBookList(c, sorted);
    }

    public static Book byId(Context c, String id) {
        if (id == null) return null;
        for (Book b : booksSnapshot(c)) {
            if (b.id().equals(id)) return b.copy();
        }
        return null;
    }

    public static void updatePosition(Context c, String id, int chapter, int offset,
                                      float fraction, float progress) {
        updatePosition(c, id, chapter, offset, fraction, progress, null);
    }

    /** Saves the reading position; {@code layoutKey} identifies the pagination it belongs to. */
    public static void updatePosition(Context c, String id, int chapter, int offset,
                                      float fraction, float progress, String layoutKey) {
        List<Book> snap = booksSnapshot(c);
        List<Book> updated = null;
        for (int i = 0; i < snap.size(); i++) {
            Book b = snap.get(i);
            if (b.id().equals(id)) {
                String nextKey = layoutKey != null ? layoutKey : b.layoutKey;
                if (b.chapter == chapter && b.offset == offset
                        && Float.compare(b.fraction, fraction) == 0
                        && Float.compare(b.progress, progress) == 0
                        && Objects.equals(b.layoutKey, nextKey)) {
                    return;
                }
                updated = new ArrayList<>(snap.size());
                for (int j = 0; j < snap.size(); j++) {
                    Book copy = snap.get(j).copy();
                    if (j == i) {
                        copy.chapter = chapter;
                        copy.offset = offset;
                        copy.fraction = fraction;
                        copy.progress = progress;
                        copy.layoutKey = nextKey;
                    }
                    updated.add(copy);
                }
                break;
            }
        }
        if (updated != null) persistBookList(c, updated);
    }

    /** Removes the library entry and its cached thumbnail; the book file itself is untouched. */
    public static void remove(Context c, String id) {
        List<Book> all = books(c);
        Iterator<Book> it = all.iterator();
        while (it.hasNext()) {
            Book b = it.next();
            if (b.id().equals(id)) {
                it.remove();
                deleteQuietly(b.coverFile);
            }
        }
        saveBooks(c, all);
        synchronized (LOCK) {
            BOOKMARK_CACHE.remove(id);
        }
        sp(c).edit().remove("bm_" + id).apply();
    }

    public static void deleteQuietly(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- bookmarks

    public static List<Bookmark> bookmarks(Context c, String id) {
        String raw = sp(c).getString("bm_" + id, "[]");
        synchronized (LOCK) {
            BookmarkCacheEntry entry = BOOKMARK_CACHE.get(id);
            if (entry != null && Objects.equals(raw, entry.raw)) {
                return new ArrayList<>(entry.items);
            }
            List<Bookmark> parsed = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    parsed.add(Bookmark.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
            BOOKMARK_CACHE.put(id,
                    new BookmarkCacheEntry(raw, Collections.unmodifiableList(parsed)));
            return new ArrayList<>(parsed);
        }
    }

    private static void saveBookmarks(Context c, String id, List<Bookmark> marks) {
        List<Bookmark> copy = new ArrayList<>(marks);
        JSONArray arr = new JSONArray();
        for (Bookmark b : copy) arr.put(b.toJson());
        String raw = arr.toString();
        synchronized (LOCK) {
            BOOKMARK_CACHE.put(id,
                    new BookmarkCacheEntry(raw, Collections.unmodifiableList(copy)));
        }
        sp(c).edit().putString("bm_" + id, raw).apply();
    }

    public static void addBookmark(Context c, String id, Bookmark bm) {
        List<Bookmark> all = bookmarks(c, id);
        all.add(bm);
        saveBookmarks(c, id, all);
    }

    public static void removeBookmark(Context c, String id, Bookmark bm) {
        List<Bookmark> all = bookmarks(c, id);
        all.removeIf(b -> b.created == bm.created && b.chapter == bm.chapter && b.offset == bm.offset);
        saveBookmarks(c, id, all);
    }
}
