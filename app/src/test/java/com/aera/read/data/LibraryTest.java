package com.aera.read.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class LibraryTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
    }

    private Book book(String uri, long addedAt) {
        Book b = new Book();
        b.uri = uri;
        b.title = "Title " + uri;
        b.addedAt = addedAt;
        return b;
    }

    @Test
    public void foldersArePersistedAndUnique() {
        Library.addFolder(app, "content://docs/tree/a");
        Library.addFolder(app, "content://docs/tree/b");
        Library.addFolder(app, "content://docs/tree/a");
        assertEquals(2, Library.folders(app).size());
        Library.removeFolder(app, "content://docs/tree/a");
        assertEquals(1, Library.folders(app).size());
        assertEquals("content://docs/tree/b", Library.folders(app).get(0));
    }

    @Test
    public void booksAreStoredNewestFirst() {
        Library.saveBooks(app, new java.util.ArrayList<>(java.util.Arrays.asList(
                book("uri-a", 100), book("uri-b", 200))));
        List<Book> books = Library.books(app);
        assertEquals(2, books.size());
        assertEquals("uri-b", books.get(0).uri);
        assertNotNull(Library.byId(app, "uri-a"));
        assertNull(Library.byId(app, "nope"));
    }

    @Test
    public void positionRoundTrip() {
        Library.saveBooks(app, new java.util.ArrayList<>(java.util.Collections.singletonList(
                book("uri-a", 100))));
        Library.updatePosition(app, "uri-a", 3, 420, 0.25f, 0.42f);
        Book stored = Library.byId(app, "uri-a");
        assertNotNull(stored);
        assertEquals(3, stored.chapter);
        assertEquals(420, stored.offset);
        assertEquals(0.25f, stored.fraction, 0.001f);
        assertEquals(0.42f, stored.progress, 0.001f);
    }

    @Test
    public void bookmarksPersistAndDelete() {
        Bookmark first = new Bookmark("Chapter 1 · 5%", 0, 120, 1000);
        Bookmark second = new Bookmark("Chapter 2 · 40%", 1, 40, 2000);
        Library.addBookmark(app, "book", first);
        Library.addBookmark(app, "book", second);

        List<Bookmark> marks = Library.bookmarks(app, "book");
        assertEquals(2, marks.size());
        assertEquals(120, marks.get(0).offset);

        Library.removeBookmark(app, "book", first);
        marks = Library.bookmarks(app, "book");
        assertEquals(1, marks.size());
        assertEquals(1, marks.get(0).chapter);
    }

    @Test
    public void removingBookDropsThumbnailAndBookmarks() throws Exception {
        Book b = book("uri-c", 300);
        File cover = File.createTempFile("cover", ".jpg");
        b.coverFile = cover.getAbsolutePath();
        Library.saveBooks(app, new java.util.ArrayList<>(java.util.Collections.singletonList(b)));
        Library.addBookmark(app, "uri-c", new Bookmark("x", 0, 0, 1));

        Library.remove(app, "uri-c");
        assertFalse(cover.exists());
        assertTrue(Library.bookmarks(app, "uri-c").isEmpty());
        assertTrue(Library.books(app).isEmpty());
    }

    @Test
    public void readerPrefsRoundTrip() {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.font = "opensans";
        prefs.sizeSp = 24;
        prefs.linePct = 165;
        prefs.theme = "night";
        prefs.viewMode = "scroll";
        prefs.direction = "rtl";
        prefs.keepScreenOn = true;
        prefs.save();

        ReaderPrefs loaded = new ReaderPrefs(app);
        assertEquals("opensans", loaded.font);
        assertEquals(24, loaded.sizeSp);
        assertEquals(165, loaded.linePct);
        assertEquals("night", loaded.theme);
        assertTrue(loaded.isScroll());
        assertTrue(loaded.isRtl());
        assertTrue(loaded.keepScreenOn);

        ReaderPrefs fresh = new ReaderPrefs(app);
        fresh.font = "lora";
        fresh.sizeSp = 18;
        fresh.linePct = 155;
        fresh.theme = "paper";
        fresh.viewMode = "pages";
        fresh.direction = "ltr";
        fresh.keepScreenOn = false;
        fresh.save();
    }

    @Test
    public void themesAreDistinctAndDarkFlagged() {
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.from("sepia"));
        assertEquals(ReaderTheme.PAPER, ReaderTheme.from("unknown"));
        assertTrue(ReaderTheme.NIGHT.dark);
        assertTrue(ReaderTheme.SLATE.dark);
        assertFalse(ReaderTheme.PAPER.dark);
        assertFalse(ReaderTheme.SEPIA.dark);
    }
}
