package com.aera.read.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import android.app.Application;

import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.epub.EpubBook;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The contents sheet lists chapters. Entries nested inside a chapter ("◆ Dungeon City
 * Sakurabana…") land in the middle of the prose, so they are not listed at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class ContentsDepthTest {

    private static EpubBook.TocEntry e(int chapter, int level, String title) {
        return new EpubBook.TocEntry(chapter, level, title);
    }

    @Test
    public void nestedSectionsAreDropped() {
        List<EpubBook.TocEntry> toc = Arrays.asList(
                e(5, 1, "Chapter 6. The Adventurer's Exam"),
                e(6, 2, "Dungeon City Sakurabana: Dungeon #27, first floor"),
                e(7, 1, "Chapter 7. The Red-Haired Lancer"),
                e(8, 2, "A short interlude"),
                e(9, 2, "Another scene"),
                e(10, 1, "Chapter 8. Homecoming"));
        List<EpubBook.TocEntry> rows = EpubSession.topLevel(toc);
        assertEquals(3, rows.size());
        assertEquals("Chapter 6. The Adventurer's Exam", rows.get(0).title);
        assertEquals("Chapter 7. The Red-Haired Lancer", rows.get(1).title);
        assertEquals("Chapter 8. Homecoming", rows.get(2).title);
        for (EpubBook.TocEntry row : rows) {
            assertEquals("only chapter-level rows", 1, row.level);
        }
    }

    @Test
    public void aSingleWrapperHeadingFallsBackToTheLevelBelow() {
        List<EpubBook.TocEntry> toc = Arrays.asList(
                e(1, 1, "The Collected Works"),
                e(2, 2, "First Essay"),
                e(3, 2, "Second Essay"),
                e(4, 2, "Third Essay"));
        List<EpubBook.TocEntry> rows = EpubSession.topLevel(toc);
        assertEquals(3, rows.size());
        assertEquals("First Essay", rows.get(0).title);
    }

    @Test
    public void aFlatContentsListIsKeptWhole() {
        List<EpubBook.TocEntry> toc = new ArrayList<>();
        for (int i = 0; i < 12; i++) toc.add(e(i, 1, "Chapter " + (i + 1)));
        assertEquals(12, EpubSession.topLevel(toc).size());
    }

    @Test
    public void meditationsListsItsChapterButNotTheSectionInsideIt() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book book = TestSupport.install(app, "meditations.epub");
        EpubSession session = EpubSession.open(app, book);
        try {
            session.layout(new com.aera.read.data.ReaderPrefs(app),
                    com.aera.read.data.ReaderTheme.from("light"), 900, 1500);
            List<EpubBook.TocEntry> rows = session.contents();
            assertFalse("the book really has a nested entry", rows.isEmpty());
            assertTrue("chapters are listed", rows.size() > 10);
            for (EpubBook.TocEntry row : rows) {
                assertEquals("no sub-sections in the list", 1, row.level);
            }
            for (EpubBook.TocEntry row : rows) {
                assertFalse("a section from inside a chapter must not be listed",
                        row.title.toLowerCase().startsWith("concerning himself"));
            }
            List<Integer> pages = pagesOf(session, rows);
            int pageCount = session.pageCount();
            for (int i = 0; i < pages.size(); i++) {
                assertTrue("page in range", pages.get(i) >= 0 && pages.get(i) < pageCount);
                if (i > 0) assertTrue("contents runs forward",
                        pages.get(i) >= pages.get(i - 1));
            }
        } finally {
            session.close();
        }
    }

    private static List<Integer> pagesOf(EpubSession session, List<EpubBook.TocEntry> rows) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(session.pageOfContents(i));
        return out;
    }
}
