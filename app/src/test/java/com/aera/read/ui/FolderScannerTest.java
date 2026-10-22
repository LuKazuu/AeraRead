package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Library;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FolderScannerTest {

    private Application app;
    private TestDocumentsProvider provider;
    private File root;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        root = new File(app.getCacheDir(), "sdcard/Books");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        provider = new TestDocumentsProvider();
        provider.setRoot(root);
        provider.register(app);
    }

    @After
    public void tearDown() {
        TestSupport.clearLibrary(app);
    }

    private void place(String relative, String sample) throws Exception {
        File dest = new File(root, relative);
        File parent = dest.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        parent.mkdirs();
        try (InputStream in = TestSupport.sample(sample);
             FileOutputStream out = new FileOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    private FolderScanner.Result scan() {
        return FolderScanner.scan(app, Collections.singletonList(TestDocumentsProvider.treeUri().toString()),
                Library.books(app), null);
    }

    @Test
    public void findsBooksRecursivelyWithoutCopying() throws Exception {
        place("meditations.epub", "meditations.epub");
        place("Novels/alice.epub", "alice.epub");
        File ignored = new File(root, "notes.txt");
        try (FileOutputStream out = new FileOutputStream(ignored)) {
            out.write("not a book".getBytes());
        }

        FolderScanner.Result result = scan();
        assertEquals(2, result.books.size());
        assertEquals(2, result.added);
        assertEquals(0, result.failed);
        for (Book b : result.books) {
            assertTrue(b.uri.startsWith("content://" + TestDocumentsProvider.AUTHORITY));
            assertFalse("covers live in the cache", b.coverFile.startsWith(root.getPath()));
            assertNotNull(b.title);
            assertFalse(b.title.isEmpty());
            assertTrue(b.size > 0);
        }
        assertTrue("titles come from the EPUB metadata",
                result.books.stream().anyMatch(b -> b.title.equals("Meditations")));
    }

    @Test
    public void rescansReuseUnchangedEntries() throws Exception {
        place("meditations.epub", "meditations.epub");
        FolderScanner.Result first = scan();
        assertEquals(1, first.added);

        FolderScanner.Result second = FolderScanner.scan(app,
                Collections.singletonList(TestDocumentsProvider.treeUri().toString()),
                first.books, null);
        assertEquals("no duplicate entries", 1, second.books.size());
        assertEquals("unchanged books are not re-parsed", 0, second.added);
        assertEquals(0, second.removed);
        assertEquals(first.books.get(0).uri, second.books.get(0).uri);
    }

    @Test
    public void deletedFilesLeaveTheLibrary() throws Exception {
        place("meditations.epub", "meditations.epub");
        File gone = new File(root, "alice.epub");
        try (InputStream in = TestSupport.sample("alice.epub");
             FileOutputStream out = new FileOutputStream(gone)) {
            in.transferTo(out);
        }
        FolderScanner.Result first = scan();
        assertEquals(2, first.books.size());

        //noinspection ResultOfMethodCallIgnored
        gone.delete();
        FolderScanner.Result second = FolderScanner.scan(app,
                Collections.singletonList(TestDocumentsProvider.treeUri().toString()),
                first.books, null);
        assertEquals(1, second.books.size());
        assertEquals(1, second.removed);
    }

    @Test
    public void reportsUnreadableFolders() {
        FolderScanner.Result result = FolderScanner.scan(app,
                Collections.singletonList("content://nobody/tree/none"), Library.books(app), null);
        assertEquals(1, result.unreadableFolders);
        assertTrue(result.books.isEmpty());
    }

    @Test
    public void describesASingleDocument() throws Exception {
        place("meditations.epub", "meditations.epub");
        File file = new File(root, "meditations.epub");
        List<Book> found = scan().books;
        assertEquals(1, found.size());
        Book book = found.get(0);
        assertEquals("Meditations", book.title);
        assertTrue(book.author.toLowerCase().contains("aurelius"));
        assertTrue(book.coverFile.length() > 0);
        assertNotNull(file);
        assertNull(Library.byId(app, "missing"));
    }

    @Test
    public void skipsHiddenAndUnsupportedFiles() throws Exception {
        place(".hidden.epub", "meditations.epub");
        place("cover.jpg", "meditations.epub");
        FolderScanner.Result result = scan();
        assertTrue(result.books.isEmpty());
    }
}
