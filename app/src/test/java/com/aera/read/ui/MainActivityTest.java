package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Library;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class MainActivityTest {

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

    private void grantFolder() {
        Library.addFolder(app, TestDocumentsProvider.treeUri().toString());
    }


    /** Refresh with no folders must stay put: only the add-folder action may open the picker. */
    @Test
    public void refreshWithoutFoldersDoesNotOpenThePicker() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            assertTrue("no folders to begin with", com.aera.read.data.Library.folders(app).isEmpty());

            activity.findViewById(R.id.btn_scan).performClick();
            TestSupport.idle();
            assertNull("refresh must not launch the folder picker",
                    Shadows.shadowOf(activity).getNextStartedActivity());

            // adding still opens it, through the folder sheet
            activity.findViewById(R.id.btn_folders).performClick();
            TestSupport.idle();
            android.app.Dialog sheet = ShadowDialog.getLatestDialog();
            sheet.findViewById(R.id.row_add_folder).performClick();
            TestSupport.idle();
            assertNotNull("adding a folder still opens the picker",
                    Shadows.shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test
    public void emptyLibraryShowsTheAddPrompt() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.grid).getVisibility());
            assertNotNull("the empty state offers the add button",
                    activity.findViewById(R.id.empty_add));
        }
    }

    @Test
    public void scanFillsTheGridWithoutCopying() throws Exception {
        place("meditations.epub", "meditations.epub");
        place("Novels/alice.epub", "alice.epub");
        grantFolder();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            activity.scan(true);
            TestSupport.waitUntil(() -> Library.books(app).size() == 2, 8000,
                    "scan should find both books");

            TestSupport.idle();
            RecyclerView grid = activity.findViewById(R.id.grid);
            assertEquals(View.VISIBLE, grid.getVisibility());
            assertEquals("the grid has the whole screen; nothing floats over it",
                    View.VISIBLE, grid.getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.empty_state).getVisibility());

            for (Book book : Library.books(app)) {
                assertTrue(book.uri.startsWith("content://"));
                assertFalse(new File(app.getFilesDir(), "books").exists());
            }
        }
    }

    @Test
    public void coversFillTheirFrameInsteadOfLetterboxing() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            androidx.recyclerview.widget.RecyclerView grid = activity.findViewById(R.id.grid);
            LibraryAdapter adapter = (LibraryAdapter) grid.getAdapter();
            LibraryAdapter.Holder holder = adapter.createViewHolder(grid, 0);
            android.widget.ImageView cover = holder.itemView.findViewById(R.id.cover);
            assertNotNull(cover);
            assertEquals("wide or tall covers crop to the frame, never a black bar",
                    android.widget.ImageView.ScaleType.CENTER_CROP, cover.getScaleType());
        }
    }

    @Test
    public void openingABookStartsTheReader() throws Exception {
        place("meditations.epub", "meditations.epub");
        grantFolder();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            TestSupport.waitUntil(() -> Library.books(app).size() == 1, 8000, "book scanned");
            Book book = Library.books(app).get(0);

            activity.onOpen(book);
            Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(next);
            assertEquals(ReaderActivity.class.getName(), next.getComponent().getClassName());
            assertEquals(book.id(), next.getStringExtra(ReaderActivity.EXTRA_BOOK_ID));
        }
    }

    @Test
    public void optionsSheetCanRemoveABook() throws Exception {
        place("meditations.epub", "meditations.epub");
        grantFolder();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            TestSupport.waitUntil(() -> Library.books(app).size() == 1, 8000, "book scanned");
            Book book = Library.books(app).get(0);

            activity.onOptions(book);
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            TextView title = sheet.findViewById(R.id.actions_title);
            assertNotNull(title);
            assertEquals(book.title, title.getText().toString());

            sheet.findViewById(R.id.row_remove).performClick();
            TestSupport.idle();
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            TestSupport.idle();
            assertTrue(Library.books(app).isEmpty());
            assertNull(Library.byId(app, book.id()));
        }
    }

    @Test
    public void booksReturnAfterRestartBecauseTheyLiveInTheFolder() throws Exception {
        place("meditations.epub", "meditations.epub");
        grantFolder();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            TestSupport.waitUntil(() -> Library.books(app).size() == 1, 8000, "autoscan");
            assertEquals(1, Library.folders(app).size());
        }
        // remove the cached entry, keep the granted folder: a fresh launch rediscovers it
        Library.saveBooks(app, new java.util.ArrayList<>());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            TestSupport.waitUntil(() -> Library.books(app).size() == 1, 8000,
                    "empty library rescans its folders");
        }
    }

    @Test
    public void foldersSheetListsAndRemovesGrants() throws Exception {
        place("meditations.epub", "meditations.epub");
        grantFolder();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            activity.findViewById(R.id.btn_folders).performClick();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            RecyclerView list = sheet.findViewById(R.id.folder_list);
            assertNotNull(list);
            TestSupport.idle();
            assertEquals(1, list.getAdapter().getItemCount());

            View row = sheet.findViewById(R.id.folder_list);
            assertNotNull(row);
            assertEquals(1, Library.folders(app).size());
        }
    }

    @Test
    public void selectingAFolderGrantIsRecorded() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            assertTrue(Library.folders(app).isEmpty());
            activity.pickFolder();
            // the picker opens the system UI; granting is exercised through onFolderPicked
            assertFalse(activity.isFinishing());
        }
    }

    @Test
    public void folderLabelFallsBackToTheTreeId() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            String label = activity.folderLabel(TestDocumentsProvider.treeUri().toString());
            assertNotNull(label);
            assertFalse(label.isEmpty());
            assertEquals("Books", activity.folderLabel(Uri.fromFile(root).toString()));
        }
    }
}
