package com.aera.read.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.ParcelFileDescriptor;

import com.aera.read.TestSupport;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class ZipArchiveTest {

    private ZipArchive open(SeekableSource source) throws IOException {
        return ZipArchive.open(source);
    }

    @Test
    public void listsAndReadsEntriesFromARealEpub() throws Exception {
        File epub = TestSupport.resourceFile("meditations.epub");
        try (ZipArchive zip = open(new FileSource(epub))) {
            List<String> names = zip.names();
            assertFalse(names.isEmpty());
            assertTrue("container entry present", zip.contains("META-INF/container.xml"));
            assertTrue("lookups are case-insensitive", zip.contains("meta-inf/container.xml"));

            byte[] bytes = zip.read("META-INF/container.xml");
            assertNotNull(bytes);
            String xml = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(xml.contains("rootfile"));

            try (InputStream in = zip.open("META-INF/container.xml")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                assertEquals(bytes.length, out.size());
            }
            assertNull("missing entries return null", zip.read("no/such/file.xhtml"));
        }
    }

    @Test
    public void everyEntryMatchesJavaZip() throws Exception {
        File epub = TestSupport.resourceFile("alice.epub");
        try (ZipArchive mine = open(new FileSource(epub)); ZipFile javaZip = new ZipFile(epub)) {
            java.util.Enumeration<? extends ZipEntry> entries = javaZip.entries();
            int checked = 0;
            while (entries.hasMoreElements() && checked < 40) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] expected = readAll(javaZip.getInputStream(entry));
                byte[] actual = mine.read(entry.getName());
                assertNotNull(entry.getName(), actual);
                assertEquals("size of " + entry.getName(), expected.length, actual.length);
                assertEquals("content of " + entry.getName(), expected.length, actual.length);
                assertTrue("bytes of " + entry.getName(),
                        java.util.Arrays.equals(expected, actual));
                checked++;
            }
            assertTrue("compared some entries", checked > 5);
        }
    }

    @Test
    public void readsThroughAFileDescriptorSource() throws Exception {
        File epub = TestSupport.resourceFile("artofwar.epub");
        byte[] expected;
        try (ZipArchive zip = open(new FileSource(epub))) {
            expected = zip.read("mimetype");
        }
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(epub,
                ParcelFileDescriptor.MODE_READ_ONLY);
        try (ZipArchive zip = open(new FdSource(pfd))) {
            byte[] actual = zip.read("mimetype");
            assertNotNull(actual);
            assertEquals(new String(expected, StandardCharsets.UTF_8).trim(),
                    new String(actual, StandardCharsets.UTF_8).trim());
            assertTrue(zip.names().size() > 3);
        }
    }

    @Test
    public void readsStoredAndDeflatedEntries() throws Exception {
        File zipFile = File.createTempFile("archive", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            ZipEntry stored = new ZipEntry("stored.txt");
            stored.setMethod(ZipEntry.STORED);
            byte[] storedData = "stored content".getBytes(StandardCharsets.UTF_8);
            stored.setSize(storedData.length);
            stored.setCompressedSize(storedData.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(storedData);
            stored.setCrc(crc.getValue());
            out.putNextEntry(stored);
            out.write(storedData);
            out.closeEntry();

            out.putNextEntry(new ZipEntry("folder/deflated.txt"));
            out.write("deflated content that is long enough to compress".getBytes(
                    StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (ZipArchive zip = open(new FileSource(zipFile))) {
            assertEquals("stored content",
                    new String(zip.read("stored.txt"), StandardCharsets.UTF_8));
            assertEquals("deflated content that is long enough to compress",
                    new String(zip.read("folder/deflated.txt"), StandardCharsets.UTF_8));
            assertEquals("stored content".length(), zip.sizeOf("stored.txt"));
        }
    }

    @Test
    public void readsEpubWithZeroPaddingAfterEndRecordAndComment() throws Exception {
        for (int padding : new int[]{0, 25, 65536}) {
            File file = File.createTempFile("padded-epub", ".epub");
            try {
                try (ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(file))) {
                    out.setComment("archive comment");
                    out.putNextEntry(new ZipEntry("META-INF/container.xml"));
                    out.write("container".getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
                    out.write(new byte[padding]);
                }
                try (ZipArchive zip = open(new FileSource(file))) {
                    assertEquals("container", new String(zip.read("META-INF/container.xml"),
                            StandardCharsets.UTF_8));
                }
            } finally {
                file.delete();
            }
        }
    }

    @Test
    public void rejectsNonZeroTrailingData() throws Exception {
        File file = File.createTempFile("trailing-epub", ".epub");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(file))) {
                out.putNextEntry(new ZipEntry("chapter.xhtml"));
                out.write(1);
                out.closeEntry();
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
                out.write(new byte[]{0, 1, 0});
            }
            try (ZipArchive ignored = open(new FileSource(file))) {
                org.junit.Assert.fail("Non-zero trailing data must not be accepted as padding");
            } catch (IOException expected) {
                // Expected.
            }
        } finally {
            file.delete();
        }
    }

    @Test(expected = IOException.class)
    public void rejectsNonZipFiles() throws Exception {
        File file = File.createTempFile("notzip", ".bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write("this is definitely not a zip archive".getBytes(StandardCharsets.UTF_8));
        }
        open(new FileSource(file));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
