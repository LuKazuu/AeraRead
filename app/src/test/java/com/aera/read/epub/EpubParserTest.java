package com.aera.read.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aera.read.TestSupport;
import com.aera.read.io.FileSource;
import com.aera.read.io.SeekableSource;
import com.aera.read.io.ZipArchive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;


@RunWith(RobolectricTestRunner.class)
public class EpubParserTest {

    private EpubBook parse(String name) throws Exception {
        EpubBook book;
        try (SeekableSource source = new FileSource(TestSupport.resourceFile(name));
             ZipArchive zip = ZipArchive.open(source)) {
            book = new EpubParser(zip).parse(name);
            // detach the archive: the test only inspects the flattened blocks
            book.zip = null;
        }
        assertNotNull(book);
        return book;
    }

    @Test
    public void parsesMetadataAndChapterStructure() throws Exception {
        EpubBook book = parse("meditations.epub");
        assertEquals("Meditations", book.title);
        assertTrue(book.author.contains("Aurelius"));
        assertTrue(book.chapters.size() > 5);
        assertNotNull("cover art extracted", book.cover);
        assertTrue(book.cover.length > 1000);
        assertFalse(book.toc.isEmpty());

        int blocks = 0;
        for (EpubBook.Chapter chapter : book.chapters) {
            blocks += chapter.blocks.size();
            assertFalse(chapter.blocks.isEmpty());
            int last = -1;
            for (EpubBook.Block b : chapter.blocks) {
                assertTrue(b.charStart >= last);
                last = b.charStart;
                if (b.type == EpubBook.Block.TEXT) {
                    assertFalse(b.text.trim().isEmpty());
                    assertFalse("whitespace normalized", b.text.contains("  "));
                }
            }
        }
        assertTrue(blocks > 100);
    }

    @Test
    public void metadataOnlyReadIsCheapAndComplete() throws Exception {
        try (SeekableSource source = new FileSource(TestSupport.resourceFile("prophet.epub"));
             ZipArchive zip = ZipArchive.open(source)) {
            EpubMetadata meta = new EpubParser(zip).readMetadata("fallback");
            assertTrue(meta.title.toLowerCase().contains("prophet"));
            assertTrue(meta.author.toLowerCase().contains("gibran"));
            assertNotNull(meta.cover);
            assertTrue(meta.cover.length > 1000);
        }
    }

    @Test
    public void resolvesTocEntriesToRealChapters() throws Exception {
        EpubBook book = parse("pride_slim.epub");
        assertEquals("Pride and Prejudice", book.title);
        assertTrue(book.toc.size() >= 5);
        for (EpubBook.TocEntry entry : book.toc) {
            assertTrue(entry.chapter >= 0);
            assertTrue(entry.chapter < book.chapters.size());
            assertFalse(entry.title.isEmpty());
            assertTrue(entry.level >= 1 && entry.level <= 3);
        }
    }

    @Test
    public void extractsInlineImages() throws Exception {
        EpubBook book = parse("alice.epub");
        int images = 0;
        for (EpubBook.Chapter chapter : book.chapters) {
            for (EpubBook.Block b : chapter.blocks) {
                if (b.type == EpubBook.Block.IMAGE) {
                    images++;
                    assertTrue(b.imgW > 0 && b.imgH > 0);
                    assertTrue(b.imagePath.contains("."));
                }
            }
        }
        assertTrue(images >= 1);
    }

    @Test
    public void preservesHeadings() throws Exception {
        EpubBook book = parse("artofwar.epub");
        boolean heading = false;
        for (EpubBook.Chapter chapter : book.chapters) {
            for (EpubBook.Block b : chapter.blocks) {
                if (b.style == EpubBook.Block.STYLE_H1 || b.style == EpubBook.Block.STYLE_H2) {
                    heading = true;
                }
            }
        }
        assertTrue(heading);
        assertTrue(book.toc.size() > 0);
    }

    @Test
    public void normalizesPaths() {
        assertEquals("OEBPS/ch1.xhtml", EpubParser.normalize("OEBPS/./ch1.xhtml"));
        assertEquals("OEBPS/ch1.xhtml", EpubParser.normalize("OEBPS/text/../ch1.xhtml"));
        assertEquals("a/b/c.png", EpubParser.normalize("./a//b/c.png"));
        assertEquals("images/x.png", EpubParser.normalize("images/../../images/x.png"));
    }

    @Test
    public void chaptersWithoutAContentsEntryGetNoInventedName() throws Exception {
        EpubBook book = parse("prophet.epub");
        java.util.Map<Integer, String> tocTitles = new java.util.HashMap<>();
        for (EpubBook.TocEntry entry : book.toc) {
            tocTitles.putIfAbsent(entry.chapter, entry.title);
        }
        for (EpubBook.Chapter chapter : book.chapters) {
            assertFalse("never a placeholder name", chapter.title.startsWith("Section "));
            if (tocTitles.containsKey(chapter.index)) {
                assertEquals(tocTitles.get(chapter.index), chapter.title);
            }
        }
    }

    @Test
    public void missingPackageDocumentFailsCleanly() throws Exception {
        java.io.File file = java.io.File.createTempFile("empty", ".epub");
        try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(file))) {
            out.putNextEntry(new java.util.zip.ZipEntry("mimetype"));
            out.write("application/epub+zip".getBytes());
            out.closeEntry();
        }
        boolean failed = false;
        try (SeekableSource source = new FileSource(file);
             ZipArchive zip = ZipArchive.open(source)) {
            new EpubParser(zip).parse("x");
        } catch (java.io.IOException e) {
            failed = true;
        }
        assertTrue(failed);
    }
}
