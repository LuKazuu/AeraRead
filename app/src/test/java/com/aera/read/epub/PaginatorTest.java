package com.aera.read.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PaginatorTest {

    private EpubBook parse(String name) throws Exception {
        try (ZipArchive zip = ZipArchive.open(new FileSource(TestSupport.resourceFile(name)))) {
            EpubBook book = new EpubParser(zip).parse(name);
            book.zip = null;
            return book;
        }
    }

    @Test
    public void paginatesEveryBlock() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("alice.epub");
        ReaderPrefs prefs = new ReaderPrefs(app);
        PagedBook paged = EpubPaginator.paginate(book, TextStyles.create(app, prefs), 900, 1500);
        assertTrue(paged.totalPages > 5);
        assertEquals(book.chapters.size(), paged.chapters.size());
        for (PagedBook.ChapterPages chapter : paged.chapters) {
            assertFalse(chapter.pages.isEmpty());
            for (PagedBook.Page page : chapter.pages) {
                assertTrue(page.startOffset <= page.endOffset);
                assertFalse(page.segments.isEmpty());
            }
        }
    }

    @Test
    public void offsetsMapBothWays() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("prophet.epub");
        PagedBook paged = EpubPaginator.paginate(book,
                TextStyles.create(app, new ReaderPrefs(app)), 800, 1400);
        int page = paged.totalPages / 2;
        PagedBook.Page p = paged.page(page);
        int offset = p.startOffset + Math.max(0, (p.endOffset - p.startOffset) / 2);
        assertEquals(page, paged.pageIndex(p.chapter, offset));
        assertTrue(paged.offsetAt(page, 0) >= p.startOffset);
        assertTrue(paged.offsetAt(page, 10000) <= p.endOffset);
        assertEquals(page, paged.chapterStartPage(p.chapter) <= page ? page : -1);
    }

    @Test
    public void pageIndexClampsOutOfRange() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("pride_slim.epub");
        PagedBook paged = EpubPaginator.paginate(book,
                TextStyles.create(app, new ReaderPrefs(app)), 800, 1400);
        assertEquals(0, paged.pageIndex(0, -50));
        assertEquals(0, paged.pageIndex(-3, 0));
        assertTrue(paged.pageIndex(book.chapters.size() + 5, 10)
                < paged.totalPages);
        assertTrue(paged.page(999999) != null);
        assertTrue(paged.page(-1) != null);
    }

    @Test
    public void largerTextProducesMorePages() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        EpubBook book = parse("artofwar.epub");
        ReaderPrefs small = new ReaderPrefs(app);
        small.sizeSp = 15;
        ReaderPrefs large = new ReaderPrefs(app);
        large.sizeSp = 27;
        PagedBook a = EpubPaginator.paginate(book, TextStyles.create(app, small), 900, 1500);
        PagedBook b = EpubPaginator.paginate(book, TextStyles.create(app, large), 900, 1500);
        assertTrue(b.totalPages > a.totalPages);
    }
}
