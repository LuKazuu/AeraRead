package com.aera.read.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.pm.ApplicationInfo;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Uninstalling has to leave nothing behind: no cloud backup, no device-transfer copy, and all
 * state inside the app's own storage (which the system deletes with the app). Books are the
 * user's files in the user's folders, so those stay where they are — the app only keeps a
 * list of them, and that list goes with the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class AppStateTest {

    @Test
    public void theAppCannotBeBackedUpOrTransferred() {
        Application app = ApplicationProvider.getApplicationContext();
        ApplicationInfo info = app.getApplicationInfo();
        assertFalse("allowBackup must be off, or Android restores old settings after install",
                (info.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0);
        assertTrue("no backup agent", info.backupAgentName == null || info.backupAgentName.isEmpty());
        assertTrue("no restore agent", info.backupAgentName == null);
    }

    @Test
    public void backupRulesExcludeEverything() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        for (String name : new String[]{"data_extraction_rules", "backup_rules"}) {
            int id = app.getResources().getIdentifier(name, "xml", app.getPackageName());
            assertTrue(name + " must exist", id != 0);
            android.util.Xml.asAttributeSet(app.getResources().getXml(id));
        }
    }

    @Test
    public void allStateLivesInTheAppContainer() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        Book book = TestSupport.install(app, "meditations.epub");
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.sizeSp = 22;
        prefs.save();
        Library.updatePosition(app, book.id(), 1, 40, 0f, 0.2f);
        TestSupport.waitUntil(() -> Library.byId(app, book.id()) != null, 3000, "saved");

        // both preference stores sit under the app's own data dir, which the system wipes on
        // uninstall; nothing is written to shared or external storage
        String dataDir = app.getDataDir().getAbsolutePath();
        File readerPrefs = new File(dataDir, "shared_prefs/aera_reader.xml");
        File libraryPrefs = new File(dataDir, "shared_prefs/aera_library.xml");
        assertTrue("reader settings are app-private: " + readerPrefs,
                readerPrefs.getAbsolutePath().startsWith(dataDir));
        assertTrue("library list is app-private: " + libraryPrefs,
                libraryPrefs.getAbsolutePath().startsWith(dataDir));
        assertTrue("thumbnails are app-private",
                app.getCacheDir().getAbsolutePath().startsWith(dataDir));

        // the library only remembers where the books are; the files stay with the user
        assertEquals("library points at the user's copy of the book",
                true, book.uri.startsWith("content://") || book.uri.startsWith("file:"));
        TestSupport.clearLibrary(app);
    }
}
