package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.Book;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/** An EPUB whose cover has transparent margins must not gain black bars. */
@RunWith(RobolectricTestRunner.class)
public class CoverPipelineTest {

    private File epubWithTransparentCover(Application app) throws Exception {
        return TestSupport.writeEpubWithCover(app, "Alpha Cover", 600, 900, 120, 120, 480, 780,
                Color.rgb(200, 60, 40));
    }

    @Test
    public void transparentCoverMarginsAreNotBlack() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        File epub = epubWithTransparentCover(app);

        Book book = FolderScanner.describe(app, "file://" + epub.getAbsolutePath(),
                "alpha.epub", epub.length(), epub.lastModified());
        assertNotNull(book);
        assertEquals("Alpha Cover", book.title);
        assertTrue("cover extracted", book.coverFile.endsWith(".jpg"));

        Bitmap saved = Thumbnails.decode(book.coverFile, 500);
        assertNotNull(saved);
        int corner = saved.getPixel(2, 2);
        assertEquals("corner must not be black", 255, Color.red(corner));
        assertEquals(255, Color.green(corner));
        assertEquals(255, Color.blue(corner));
        int centre = saved.getPixel(saved.getWidth() / 2, saved.getHeight() / 2);
        assertTrue("cover art kept", Color.red(centre) > 150 && Color.green(centre) < 110);
    }
}
