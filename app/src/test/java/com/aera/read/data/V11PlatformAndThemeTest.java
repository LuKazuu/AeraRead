package com.aera.read.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.R;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.ui.CoverImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Verifies v1.1 platform target/min SDK metadata, Light theme replacement (zero effects),
 * PDF full-theme preference persistence, CoverImageView 3:4 geometry without ConstraintLayout,
 * and ZipArchive multi-chunk stored/deflated stream integrity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class V11PlatformAndThemeTest {

    @Test
    public void manifestTargetsAndroid9ThroughAndroid17AtVersion11() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ApplicationInfo appInfo = app.getApplicationInfo();
        PackageInfo pkgInfo = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);

        assertEquals("minSdk must be Android 9 (API 28)", 28, appInfo.minSdkVersion);
        assertEquals("targetSdk must be Android 17 (API 37)", 37, appInfo.targetSdkVersion);
        assertEquals("1.1", pkgInfo.versionName);
        assertEquals(2L, pkgInfo.getLongVersionCode());
    }

    @Test
    public void lightThemeReplacesPaperAndAppliesZeroVisualEffects() {
        ReaderTheme[] values = ReaderTheme.values();
        assertEquals(4, values.length);
        assertEquals(ReaderTheme.LIGHT, values[0]);
        assertEquals(ReaderTheme.SEPIA, values[1]);
        assertEquals(ReaderTheme.SLATE, values[2]);
        assertEquals(ReaderTheme.NIGHT, values[3]);

        for (ReaderTheme theme : values) {
            assertFalse("Paper theme must be gone", "paper".equalsIgnoreCase(theme.key));
        }

        assertEquals(ReaderTheme.LIGHT, ReaderTheme.from("light"));
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.from(null));
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.from("unknown"));
        assertEquals(0xFFFFFFFF, ReaderTheme.LIGHT.bg);
        assertFalse(ReaderTheme.LIGHT.dark);
        assertNull("Light theme must apply zero PDF page filters",
                ReaderTheme.LIGHT.pdfColorFilter(true));
        assertNull("Light theme background-only must apply zero filters",
                ReaderTheme.LIGHT.pdfColorFilter(false));

        assertNotNull(ReaderTheme.SEPIA.pdfColorFilter(true));
        assertNotNull(ReaderTheme.SLATE.pdfColorFilter(true));
        assertNotNull(ReaderTheme.NIGHT.pdfColorFilter(true));
        assertNull("Background-only disables PDF page filter",
                ReaderTheme.NIGHT.pdfColorFilter(false));
    }

    @Test
    public void readerPrefsDefaultToLightAndFullPagePdfThemeAndPersist() {
        Application app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("aera_reader", Application.MODE_PRIVATE).edit().clear().commit();

        ReaderPrefs defaults = new ReaderPrefs(app);
        assertEquals("light", defaults.theme);
        assertTrue("PDF theme must default to Full Page", defaults.pdfFullTheme);

        defaults.theme = "slate";
        defaults.pdfFullTheme = false;
        defaults.save();

        ReaderPrefs reloaded = new ReaderPrefs(app);
        assertEquals("slate", reloaded.theme);
        assertFalse(reloaded.pdfFullTheme);

        reloaded.theme = "light";
        reloaded.pdfFullTheme = true;
        reloaded.save();
    }

    @Test
    public void bookCopyClonesEveryFieldIndependently() {
        Book src = new Book();
        src.uri = "file:///books/test.epub";
        src.title = "Title";
        src.author = "Author";
        src.type = Book.TYPE_EPUB;
        src.size = 12345L;
        src.lastModified = 67890L;
        src.addedAt = 111L;
        src.coverFile = "thumb.png";
        src.chapter = 3;
        src.offset = 450;
        src.fraction = 0.25f;
        src.progress = 0.6f;
        src.layoutKey = "lora:18:155:800x1200";

        Book copy = src.copy();
        assertNotSame(src, copy);
        assertEquals(src.uri, copy.uri);
        assertEquals(src.title, copy.title);
        assertEquals(src.author, copy.author);
        assertEquals(src.type, copy.type);
        assertEquals(src.size, copy.size);
        assertEquals(src.coverFile, copy.coverFile);
        assertEquals(src.chapter, copy.chapter);
        assertEquals(src.offset, copy.offset);
        assertEquals(src.fraction, copy.fraction, 0.0001f);
        assertEquals(src.progress, copy.progress, 0.0001f);
        assertEquals(src.layoutKey, copy.layoutKey);

        copy.title = "Mutated";
        assertEquals("Title", src.title);
    }

    @Test
    public void coverImageViewMeasuresExactThreeByFourAspectWithoutConstraintLayout() {
        Application app = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(app, R.style.Theme_Aera);
        View card = LayoutInflater.from(themed).inflate(R.layout.item_book, null, false);
        CoverImageView cover = card.findViewById(R.id.cover);
        assertNotNull(cover);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        cover.measure(widthSpec, heightSpec);
        assertEquals(300, cover.getMeasuredWidth());
        assertEquals(400, cover.getMeasuredHeight());
    }

    @Test
    public void zipArchiveStreamsStoredAndDeflatedEntriesAcrossSmallChunkReadsWithoutTruncation()
            throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        byte[] storedPayload = new byte[24_576];
        byte[] deflatedPayload = new byte[49_152];
        Random rng = new Random(42L);
        rng.nextBytes(storedPayload);
        for (int i = 0; i < deflatedPayload.length; i++) {
            deflatedPayload[i] = (byte) ((i * 31 + (i >>> 5)) & 0xFF);
        }

        File zipFile = new File(app.getCacheDir(), "stream-chunks-test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry stored = new ZipEntry("images/photo.jpg");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(storedPayload.length);
            stored.setCompressedSize(storedPayload.length);
            CRC32 crc = new CRC32();
            crc.update(storedPayload);
            stored.setCrc(crc.getValue());
            zos.putNextEntry(stored);
            zos.write(storedPayload);
            zos.closeEntry();

            ZipEntry deflated = new ZipEntry("text/ch1.xhtml");
            deflated.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(deflated);
            zos.write(deflatedPayload);
            zos.closeEntry();
        }

        try (ZipArchive archive = ZipArchive.open(new FileSource(zipFile))) {
            assertArrayEquals(storedPayload, readInSmallChunks(archive, "images/photo.jpg", 1024));
            assertArrayEquals(storedPayload, readInSmallChunks(archive, "images/photo.jpg", 4096));
            assertArrayEquals(deflatedPayload, readInSmallChunks(archive, "text/ch1.xhtml", 513));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
        }
    }

    private static byte[] readInSmallChunks(ZipArchive archive, String entry, int chunkSize)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[chunkSize];
        try (InputStream in = archive.open(entry)) {
            assertEquals("zero-length read must return 0", 0, in.read(buf, 0, 0));
            int n;
            while ((n = in.read(buf, 0, buf.length)) >= 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }
}
