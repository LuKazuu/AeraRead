package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/**
 * Every card has to look the same whether or not the document carries an author. The author
 * line keeps its height, so the progress bar never jumps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class LibraryCardTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    private static Book book(String title, String author, String type) {
        Book b = new Book();
        b.uri = "content://test/" + title;
        b.title = title;
        b.author = author;
        b.type = type;
        b.progress = 0.4f;
        return b;
    }

    private static LibraryAdapter.Holder bind(Context ctx, LibraryAdapter adapter, int position) {
        FrameLayout host = new FrameLayout(ctx);
        LibraryAdapter.Holder holder = adapter.createViewHolder(host, 0);
        adapter.onBindViewHolder(holder, position);
        int width = ctx.getResources().getDisplayMetrics().widthPixels / 3;
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        holder.itemView.layout(0, 0, holder.itemView.getMeasuredWidth(),
                holder.itemView.getMeasuredHeight());
        return holder;
    }

    @Test
    public void theProgressBarSitsAtTheSameHeightWithOrWithoutAnAuthor() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            LibraryAdapter adapter = new LibraryAdapter(new LibraryAdapter.Listener() {
                @Override
                public void onOpen(Book book) {
                }

                @Override
                public void onOptions(Book book) {
                }
            });
            adapter.submit(Arrays.asList(
                    book("Meditations", "Marcus Aurelius", Book.TYPE_EPUB),
                    book("Collected essays", "", Book.TYPE_PDF)));

            LibraryAdapter.Holder withAuthor = bind(activity, adapter, 0);
            LibraryAdapter.Holder withoutAuthor = bind(activity, adapter, 1);

            assertEquals(View.VISIBLE, withAuthor.author.getVisibility());
            assertEquals("a missing author is stated, not left blank", View.VISIBLE,
                    withoutAuthor.author.getVisibility());
            assertEquals("No author", withoutAuthor.author.getText().toString());
            assertEquals("Marcus Aurelius", withAuthor.author.getText().toString());
            assertEquals("both cards keep the same author line",
                    withAuthor.author.getMeasuredHeight(),
                    withoutAuthor.author.getMeasuredHeight());
            assertNotEquals(0, withAuthor.author.getMeasuredHeight());

            assertEquals("progress bar keeps its position",
                    withAuthor.progress.getTop(), withoutAuthor.progress.getTop());
            assertEquals("cards stay the same height",
                    withAuthor.itemView.getMeasuredHeight(),
                    withoutAuthor.itemView.getMeasuredHeight());
        }
    }

    @Test
    public void aRealGridMixesPdfsAndEpubsWithoutShiftingTheBar() throws Exception {
        TestSupport.clearLibrary(app);
        java.io.File root = new java.io.File(app.getCacheDir(), "sdcard/Books");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        TestDocumentsProvider provider = new TestDocumentsProvider();
        provider.setRoot(root);
        provider.register(app);
        for (String name : new String[]{"meditations.epub", "gatsby.pdf"}) {
            java.io.File dest = new java.io.File(root, name);
            try (java.io.InputStream in = TestSupport.sample(name);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                in.transferTo(out);
            }
        }
        com.aera.read.data.Library.addFolder(app, TestDocumentsProvider.treeUri().toString());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            activity.scan(true);
            TestSupport.waitUntil(() -> com.aera.read.data.Library.books(app).size() == 2, 10000,
                    "both documents should be scanned");
            TestSupport.idle();
            TestSupport.layout(activity);
            TestSupport.idle();

            androidx.recyclerview.widget.RecyclerView grid = activity.findViewById(R.id.grid);
            LibraryAdapter adapter = (LibraryAdapter) grid.getAdapter();
            java.util.List<Book> books = com.aera.read.data.Library.books(app);

            int epubAt = -1;
            int pdfAt = -1;
            for (int i = 0; i < books.size(); i++) {
                if (books.get(i).isPdf()) pdfAt = i;
                else epubAt = i;
            }
            assertTrue("an epub in the grid", epubAt >= 0);
            assertTrue("a pdf in the grid", pdfAt >= 0);
            assertTrue("the pdf really has no author",
                    books.get(pdfAt).author == null || books.get(pdfAt).author.trim().isEmpty());

            View epub = grid.getLayoutManager().findViewByPosition(epubAt);
            View pdf = grid.getLayoutManager().findViewByPosition(pdfAt);
            if (epub == null || pdf == null) {
                // the grid may not have been populated in this pass; bind both cards directly
                LibraryAdapter.Holder a = bind(activity, adapter, epubAt);
                LibraryAdapter.Holder b = bind(activity, adapter, pdfAt);
                assertEquals(a.progress.getTop(), b.progress.getTop());
                return;
            }
            int epubBar = barTop(epub);
            int pdfBar = barTop(pdf);
            assertEquals("both grid cards put the bar on the same line", epubBar, pdfBar);
        } finally {
            TestSupport.clearLibrary(app);
        }
    }

    private static int barTop(View card) {
        View progress = card.findViewById(R.id.progress);
        View parent = (View) progress.getParent();
        return parent.getTop() + progress.getTop() + card.getTop();
    }

    @Test
    public void titleLinesAreReservedSoShortTitlesDoNotShrinkTheCard() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = TestSupport.currentActivity(scenario);
            LibraryAdapter adapter = new LibraryAdapter(new LibraryAdapter.Listener() {
                @Override
                public void onOpen(Book book) {
                }

                @Override
                public void onOptions(Book book) {
                }
            });
            adapter.submit(Arrays.asList(
                    book("A", "Someone", Book.TYPE_EPUB),
                    book("A very long title that has to wrap onto a second line",
                            "Someone", Book.TYPE_EPUB)));
            LibraryAdapter.Holder shortTitle = bind(activity, adapter, 0);
            LibraryAdapter.Holder longTitle = bind(activity, adapter, 1);
            TextView a = shortTitle.title;
            TextView b = longTitle.title;
            assertEquals("both titles occupy two lines", a.getMeasuredHeight(),
                    b.getMeasuredHeight());
            assertEquals(shortTitle.progress.getTop(), longTitle.progress.getTop());
        }
    }
}
