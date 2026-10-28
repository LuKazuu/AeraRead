package com.aera.read;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.aera.read.data.Book;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;
import com.aera.read.reader.EpubSession;
import com.aera.read.ui.PageView;
import com.aera.read.ui.ReaderActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.2 performance & RAM proof for the tap-navigation path (Robolectric NATIVE graphics,
 * SDK 37): cold open, full-book pagination, single-tap glide settle, 30 back-to-back tap
 * glides, PageView draw throughput, and Java-heap + process-RSS stability under 200 taps.
 * All measured numbers are asserted against generous ceilings AND rendered to a PNG
 * dashboard (build/proof-screenshots/07-tap-performance-v1.1.2.png) as visual proof.
 * Timings are host-CPU work under the Robolectric JVM, not device wall-clock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class TapGlidePerformanceProofTest {

    private Application app;
    private final List<String[]> rows = new ArrayList<>();

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.theme = "light";
        prefs.viewMode = "scroll";
        prefs.direction = "ltr";
        prefs.font = "system";
        prefs.sizeSp = 18;
        prefs.linePct = 155;
        prefs.tapToTurn = true;
        prefs.save();
    }

    @After
    public void tearDown() {
        TestSupport.clearLibrary(app);
    }

    @Test
    public void tapNavigationIsFastAndMemoryStableWithScreenshotProof() throws Exception {
        long rssStart = processRssKb();

        // ---- 1. cold open of the reader (activity launch -> paginated session ready)
        Book book = TestSupport.install(app, "meditations.epub");
        long t0 = System.nanoTime();
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 60000, "session");
        TestSupport.layout(activity);
        TestSupport.idle();
        long openMs = ms(t0);
        int pageCount = activity.getSession().pageCount();
        add("Cold open -> paginated session (" + pageCount + " pages)",
                openMs + " ms", openMs < 30000);

        // ---- 2. full-book re-pagination (typography relayout path)
        EpubSession fresh = EpubSession.open(app, book);
        long t1 = System.nanoTime();
        fresh.layout(new ReaderPrefs(app), ReaderTheme.LIGHT, 822, 1782);
        long paginateMs = ms(t1);
        fresh.close();
        add("Full-book pagination (822x1782)", paginateMs + " ms", paginateMs < 20000);

        // ---- 3. single tap glide: dispatch -> fully settled
        android.view.View frame = activity.findViewById(R.id.content_frame);
        int step = (int) (frame.getHeight() * ReaderActivity.TAP_SCROLL_FRACTION);
        androidx.recyclerview.widget.RecyclerView list =
                activity.findViewById(R.id.scroll_list);
        int off0 = list.computeVerticalScrollOffset();
        long t2 = System.nanoTime();
        tap(activity, frame.getWidth() * 0.92f, frame.getHeight() * 0.5f);
        settleGlide();
        long singleTapMs = ms(t2);
        int off1 = list.computeVerticalScrollOffset();
        assertTrue("tap settled to exact reduced step (" + (off1 - off0) + " == " + step + ")",
                off1 - off0 == step);
        add("Single tap glide settle (exact " + step + "px = 60% viewport)",
                singleTapMs + " ms", singleTapMs < 3000);

        // ---- 4. 30 back-to-back fast taps (additive glide, zero hold-back)
        long t3 = System.nanoTime();
        int base = list.computeVerticalScrollOffset();
        for (int i = 0; i < 30; i++) {
            tap(activity, frame.getWidth() * 0.92f, frame.getHeight() * 0.5f);
            advanceFrames(2);
        }
        settleGlide();
        long burstMs = ms(t3);
        int moved = list.computeVerticalScrollOffset() - base;
        assertTrue("every one of 30 fast taps accumulated (moved " + moved + "px)",
                moved > 25 * step);
        add("30 back-to-back tap glides (" + moved + "px total)",
                burstMs + " ms  (" + (burstMs / 30.0) + " ms/tap)", burstMs < 20000);

        // ---- 5. PageView draw throughput at full viewport size
        EpubBook parsed;
        try (ZipArchive zip = ZipArchive.open(
                new FileSource(TestSupport.resourceFile("meditations.epub")))) {
            parsed = new EpubParser(zip).parse("meditations.epub");
            parsed.zip = null;
        }
        PagedBook paged = EpubPaginator.paginate(
                parsed, TextStyles.create(app, new ReaderPrefs(app)), 822, 1782);
        PageView pv = new PageView(app);
        pv.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY));
        pv.layout(0, 0, 822, 1782);
        PagedBook.Page page = paged.page(Math.min(5, paged.totalPages - 1));
        pv.setData(paged, page, ReaderTheme.LIGHT, null);
        TestSupport.idle();
        Bitmap target = Bitmap.createBitmap(822, 1782, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        pv.draw(canvas); // warm
        long t4 = System.nanoTime();
        int frames = 20;
        for (int i = 0; i < frames; i++) pv.draw(canvas);
        long drawTotalNs = System.nanoTime() - t4;
        double avgDrawMs = drawTotalNs / 1_000_000.0 / frames;
        target.recycle();
        add("PageView full-page draw (20 frames avg)",
                String.format(java.util.Locale.US, "%.1f ms/frame", avgDrawMs),
                avgDrawMs < 250);

        // ---- 6. RAM stability: 200 taps in Pages mode, heap delta after full GC
        scenario.close();
        ReaderPrefs pages = new ReaderPrefs(app);
        pages.viewMode = "pages";
        pages.tapToTurn = true;
        pages.save();
        ActivityScenario<ReaderActivity> scenario2 = ActivityScenario.launch(
                new Intent(app, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ReaderActivity activity2 = TestSupport.currentActivity(scenario2);
        TestSupport.waitUntil(() -> activity2.getSession() != null, 60000, "session2");
        TestSupport.layout(activity2);
        TestSupport.idle();
        View frame2 = activity2.findViewById(R.id.content_frame);

        gcSettle();
        long heapBefore = usedHeapKb();
        long t5 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            tap(activity2, frame2.getWidth() * (i % 2 == 0 ? 0.92f : 0.08f),
                    frame2.getHeight() * 0.5f);
            if (i % 4 == 3) advanceFrames(1);
        }
        settleGlide();
        long taps200Ms = ms(t5);
        gcSettle();
        long heapAfter = usedHeapKb();
        long heapDeltaKb = heapAfter - heapBefore;
        scenario2.close();
        add("200 alternating taps (forward+back)", taps200Ms + " ms", taps200Ms < 60000);
        add("Java heap delta across those 200 taps (after GC)",
                (heapDeltaKb >= 0 ? "+" : "") + heapDeltaKb + " kB",
                Math.abs(heapDeltaKb) < 12_000);

        // ---- 7. whole-test process RSS movement (JVM + Skia native graphics)
        long rssEnd = processRssKb();
        add("Process RSS start -> end (whole proof run)",
                rssStart + " kB -> " + rssEnd + " kB",
                rssEnd - rssStart < 400_000);
        add("Environment",
                Runtime.getRuntime().availableProcessors() + " cores, JVM max "
                        + (Runtime.getRuntime().maxMemory() / 1024) + " kB", true);

        // ---- render the PNG dashboard proof
        File outDir = new File("build/proof-screenshots");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File png = new File(outDir, "07-tap-performance-v1.1.2.png");
        renderDashboard(png);
        assertTrue("performance proof PNG written", png.isFile() && png.length() > 4096);
        Bitmap decoded = BitmapFactory.decodeFile(png.getAbsolutePath());
        assertNotNull(decoded);
    }

    // ------------------------------------------------------------------ metrics helpers

    private void add(String metric, String value, boolean pass) {
        rows.add(new String[]{metric, value, pass ? "PASS" : "FAIL"});
        assertTrue(metric + " -> " + value, pass);
    }

    private static long ms(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000L;
    }

    private static long usedHeapKb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024L;
    }

    private static void gcSettle() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long processRssKb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void tap(ReaderActivity activity, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0);
        activity.dispatchTouchEvent(down);
        activity.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void advanceFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            boolean[] vsyncFired = {false};
            android.view.Choreographer.getInstance()
                    .postFrameCallback(ns -> vsyncFired[0] = true);
            int guard = 0;
            while (!vsyncFired[0] && guard++ < 10000) {
                Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
            }
        }
    }

    /** Drives vsync until the 180 ms glide animation has fully settled. */
    private static void settleGlide() {
        advanceFrames(24);
        TestSupport.idle();
    }

    // ------------------------------------------------------------------ dashboard render

    private void renderDashboard(File dest) throws Exception {
        int w = 1280;
        int rowH = 54;
        int headerH = 150;
        int h = headerH + rows.size() * rowH + 60;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF0F172A);

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextSize(30f);
        c.drawText("Aera Read v1.1.2 — Tap Navigation Performance & RAM Proof", 36, 58, title);

        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(0xFF94A3B8);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setTextSize(19f);
        c.drawText("Robolectric 4.17 · NATIVE graphics (Skia + FreeType) · SDK 37 · "
                + "measured on host JVM (2 cores, swap-backed)", 36, 94, sub);
        c.drawText("Scroll-mode tap step = 60% viewport (reduced from 85%) · edge taps "
                + "opt-in via Appearance switch", 36, 122, sub);

        Paint metric = new Paint(Paint.ANTI_ALIAS_FLAG);
        metric.setColor(0xFFE2E8F0);
        metric.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        metric.setTextSize(21f);
        Paint value = new Paint(metric);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        Paint pass = new Paint(Paint.ANTI_ALIAS_FLAG);
        pass.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        pass.setTextSize(21f);
        Paint line = new Paint();
        line.setColor(0xFF1E293B);
        line.setStrokeWidth(2f);

        int y = headerH + 30;
        for (String[] row : rows) {
            c.drawText(row[0], 36, y, metric);
            c.drawText(row[1], 760, y, value);
            pass.setColor(row[2].equals("PASS") ? 0xFF4ADE80 : 0xFFF87171);
            c.drawText(row[2], 1160, y, pass);
            c.drawLine(36, y + 18, w - 36, y + 18, line);
            y += rowH;
        }
        try (FileOutputStream out = new FileOutputStream(dest)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bmp.recycle();
    }
}
