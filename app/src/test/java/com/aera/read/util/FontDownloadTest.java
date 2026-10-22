package com.aera.read.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Downloading, failing, and deleting fonts, including the ways a download can go wrong. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class FontDownloadTest {

    private Application app;
    private final List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
    private final List<Boolean> finishes = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        progress.clear();
        finishes.clear();
        FontStore.setDownloader(null);
    }

    private FontStore.Listener listener() {
        return new FontStore.Listener() {
            @Override
            public void onProgress(String id, int percent) {
                progress.add(percent);
            }

            @Override
            public void onFinished(String id, boolean ok) {
                finishes.add(ok);
            }
        };
    }

    /** Starts a download and returns true when it reported success. */
    private boolean run(FontCatalog.Entry entry) {
        FontStore.download(app, entry, listener());
        TestSupport.waitUntil(() -> !finishes.isEmpty(), 15000, "download finished");
        return finishes.get(finishes.size() - 1);
    }

    @Test
    public void aGoodDownloadIsStoredByteForByteAndCanBeRemoved() throws Exception {
        final byte[] bytes = TestSupport.readFont("lora");
        FontStore.setDownloader(url -> new FontStore.Body(new ByteArrayInputStream(bytes),
                bytes.length));

        assertTrue(run(FontCatalog.byId("lora")));
        assertTrue(FontStore.isInstalled(app, "lora"));
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(
                FontStore.file(app, "lora").toPath()));
        assertEquals("ends at the total", 100, progress.get(progress.size() - 1).intValue());
        assertTrue(progress.size() > 1);

        assertTrue(FontStore.remove(app, "lora"));
        assertFalse(FontStore.isInstalled(app, "lora"));
        assertEquals(0, files().length);
    }

    @Test
    public void anHttpErrorLeavesNothingBehind() {
        FontStore.setDownloader(url -> {
            throw new IOException("HTTP 404");
        });
        assertFalse(run(FontCatalog.byId("lora")));
        assertFalse(FontStore.isInstalled(app, "lora"));
        assertEquals("no half files", 0, files().length);
    }

    @Test
    public void aTruncatedBodyIsRejected() throws Exception {
        byte[] bytes = TestSupport.readFont("inter");
        // the server promises the full length but stops after a third of it
        FontStore.setDownloader(url -> new FontStore.Body(
                new ByteArrayInputStream(bytes, 0, bytes.length / 3), bytes.length));
        assertFalse(run(FontCatalog.byId("inter")));
        assertFalse(FontStore.isInstalled(app, "inter"));
        assertEquals(0, files().length);
    }

    @Test
    public void anErrorPageInsteadOfAFontIsRejected() {
        byte[] html = "<html><body>rate limited</body></html>"
                .repeat(100).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        FontStore.setDownloader(url -> new FontStore.Body(new ByteArrayInputStream(html),
                html.length));
        assertFalse(run(FontCatalog.byId("bitter")));
        assertFalse(FontStore.isInstalled(app, "bitter"));
    }

    @Test
    public void aSecondTapOnTheSameFontDoesNotStartASecondDownload() throws Exception {
        final byte[] bytes = TestSupport.readFont("opensans");
        final CountDownLatch release = new CountDownLatch(1);
        final int[] opens = new int[1];
        FontStore.setDownloader(url -> {
            opens[0]++;
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new FontStore.Body(new ByteArrayInputStream(bytes), bytes.length);
        });
        FontCatalog.Entry entry = FontCatalog.byId("opensans");
        FontStore.download(app, entry, listener());
        FontStore.download(app, entry, listener());
        assertTrue(FontStore.isDownloading("opensans"));
        release.countDown();
        TestSupport.waitUntil(() -> FontStore.isInstalled(app, "opensans"), 15000, "installed");
        assertEquals(1, opens[0]);
    }

    @Test
    public void twoDifferentFontsCanDownloadAtOnce() throws Exception {
        final byte[] lora = TestSupport.readFont("lora");
        final byte[] inter = TestSupport.readFont("inter");
        FontStore.setDownloader(url -> new FontStore.Body(
                new ByteArrayInputStream(url.contains("lora") ? lora : inter),
                url.contains("lora") ? lora.length : inter.length));
        FontStore.download(app, FontCatalog.byId("lora"), listener());
        FontStore.download(app, FontCatalog.byId("inter"), listener());
        TestSupport.waitUntil(() -> FontStore.isInstalled(app, "lora")
                && FontStore.isInstalled(app, "inter"), 15000, "both installed");
        assertArrayEquals(lora, java.nio.file.Files.readAllBytes(
                FontStore.file(app, "lora").toPath()));
        assertArrayEquals(inter, java.nio.file.Files.readAllBytes(
                FontStore.file(app, "inter").toPath()));
    }

    @Test
    public void aServerThatSendsNoLengthStillInstalls() throws Exception {
        final byte[] bytes = TestSupport.readFont("lora");
        FontStore.setDownloader(url -> new FontStore.Body(new ByteArrayInputStream(bytes), -1));
        assertTrue(run(FontCatalog.byId("lora")));
        assertTrue(FontStore.isInstalled(app, "lora"));
    }

    @Test
    public void halfWrittenFilesFromAKilledDownloadAreCleanedUp() throws Exception {
        File orphan = new File(FontStore.dir(app), "lora.ttf.part");
        java.nio.file.Files.write(orphan.toPath(), new byte[]{1, 2, 3});
        FontStore.cleanPartials(app);
        assertFalse(orphan.exists());
    }

    @Test
    public void systemAndUnknownKeysCanNotBeDeletedOrDownloaded() {
        assertFalse(FontStore.remove(app, Fonts.SYSTEM));
        assertFalse(FontStore.remove(app, null));
        assertFalse(FontStore.remove(app, "not-in-catalog"));
        assertFalse(FontStore.isInstalled(app, Fonts.SYSTEM));
        assertFalse(FontStore.isInstalled(app, null));
        assertFalse(FontStore.isInstalled(app, "not-in-catalog"));
        FontStore.download(app, null, listener());
        assertTrue(finishes.isEmpty());
    }

    private File[] files() {
        File[] all = FontStore.dir(app).listFiles();
        return all == null ? new File[0] : all;
    }
}
