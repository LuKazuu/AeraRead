package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.aera.read.R;
import com.aera.read.TestPdfSource;
import com.aera.read.TestSupport;
import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.pdf.PdfRendererSource;
import com.aera.read.reader.PdfSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Verifies end-to-end EPUB and PDF pinch-to-zoom across both Scroll View and Pages View,
 * specifically ensuring that pinching on a book's short "Contents" page in Scroll View
 * zooms the entire visible viewport seamlessly rather than isolating a single PageView box.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37, qualifiers = "w411dp-h740dp-xhdpi")
public class ScrollZoomTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        TestSupport.clearLibrary(app);
        TestSupport.installFont(app, "lora");
        TestSupport.installFont(app, "inter");
        TestSupport.installFont(app, "opensans");
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.theme = "light";
        prefs.viewMode = "scroll";
        prefs.direction = "ltr";
        prefs.sizeSp = 18;
        prefs.linePct = 155;
        prefs.font = "lora";
        prefs.keepScreenOn = false;
        prefs.save();
    }

    @After
    public void tearDown() {
        PdfSession.sourceFactory = PdfRendererSource::open;
        TestSupport.clearLibrary(app);
    }

    @Test
    public void epubScrollPinchOnContentsZoomsEntireViewportTogether() throws Exception {
        Book book = installEpubWithShortContents("Contents Zoom Book");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ZoomRecyclerView scrollList = activity.findViewById(R.id.scroll_list);
            assertEquals(View.VISIBLE, scrollList.getVisibility());

            // Scroll partway down so BOTH Page 0 ("Contents") and Page 1 ("Chapter One")
            // are visible simultaneously on the same screen.
            scenario.onActivity(a -> {
                scrollList.scrollBy(0, 740);
                TestSupport.layout(a);
            });
            TestSupport.idle();
            assertTrue("two pages must share the visible scroll viewport",
                    scrollList.getChildCount() >= 2);

            PageView contentsView = (PageView) scrollList.getChildAt(0);
            PageView chapterView = (PageView) scrollList.getChildAt(1);

            Bitmap beforeBmp = renderView(scrollList);
            int beforeBottomInk = countInkPixels(beforeBmp,
                    scrollList.getHeight() / 2, scrollList.getHeight());
            assertTrue("adjacent Chapter One page has visible text before pinch",
                    beforeBottomInk > 100);

            // Pinch on the upper "Contents" portion of the screen (y = 260, inside PageView 0).
            scenario.onActivity(a -> pinchAt(scrollList, 411f, 260f, 60f, 760f));
            TestSupport.idle();

            assertTrue("the whole scroll viewport must zoom", scrollList.isZoomed());
            assertTrue("magnification > 1.8x", scrollList.zoomScale() > 1.8f);
            assertFalse("Contents PageView must NOT zoom inside an isolated clipped box",
                    contentsView.isZoomed());
            assertFalse("Chapter PageView must NOT zoom independently",
                    chapterView.isZoomed());

            // Render the zoomed viewport: BOTH upper ("Contents") and lower ("Chapter One")
            // regions reflect the unified viewport transformation rather than leaving the
            // lower half unzoomed at 1.0x.
            Bitmap afterBmp = renderView(scrollList);
            assertFalse("lower half of viewport must ALSO transform with the whole-page zoom",
                    regionsIdentical(beforeBmp, afterBmp,
                            scrollList.getHeight() / 2, scrollList.getHeight()));
        }
    }

    @Test
    public void epubScrollZoomedPanScrollsSeamlesslyAcrossPages() throws Exception {
        Book book = installEpubWithShortContents("Seamless Pan Book");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ZoomRecyclerView scrollList = activity.findViewById(R.id.scroll_list);
            assertEquals(0, activity.getCurrentPage());

            scenario.onActivity(a -> pinchAt(scrollList, 411f, 500f, 60f, 760f));
            TestSupport.idle();
            assertTrue(scrollList.isZoomed());

            // Pan upward across several strokes while zoomed — must cross past Contents (page 0)
            // straight into Chapter One / Two without getting trapped inside page 0's box!
            for (int i = 0; i < 6; i++) {
                scenario.onActivity(a -> dragOneFinger(scrollList, 411f, 1150f, 411f, 250f));
                TestSupport.idle();
            }

            assertTrue("viewport stays zoomed while scrolling across chapters",
                    scrollList.isZoomed());
            assertTrue("zoomed vertical drag crossed seamlessly past the Contents page",
                    activity.getCurrentPage() >= 1);
        }
    }

    @Test
    public void singleTapResetsScrollViewportZoomWithoutTurningPage() throws Exception {
        Book book = installEpubWithShortContents("Tap Reset Book");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ZoomRecyclerView scrollList = activity.findViewById(R.id.scroll_list);

            scenario.onActivity(a -> {
                scrollList.scrollBy(0, 400);
                TestSupport.layout(a);
                pinchAt(scrollList, 411f, 600f, 60f, 760f);
            });
            TestSupport.idle();
            assertTrue(scrollList.isZoomed());
            int pageBeforeTap = activity.getCurrentPage();

            // Tap inside the left page-turn zone — must reset zoom instead of turning page.
            scenario.onActivity(a -> tapActivity(a, 80f, 700f));
            TestSupport.idle();

            assertFalse("single tap resets scroll zoom cleanly", scrollList.isZoomed());
            assertEquals(1f, scrollList.zoomScale(), 0.001f);
            assertEquals("tap-to-unzoom must not turn the page",
                    pageBeforeTap, activity.getCurrentPage());
        }
    }

    @Test
    public void pdfScrollPinchZoomsWholeViewportAndRequestsSharpRender() throws Exception {
        AtomicInteger maxRenderedWidth = new AtomicInteger(0);
        PdfSession.sourceFactory = (context, b) -> new TestPdfSource(4) {
            @Override
            public Bitmap render(int page, int targetWidthPx) {
                maxRenderedWidth.accumulateAndGet(targetWidthPx, Math::max);
                return super.render(page, targetWidthPx);
            }
        };
        Book pdf = TestSupport.installFakePdf(app, "Scroll Zoom PDF");

        try (ActivityScenario<ReaderActivity> scenario = open(pdf)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ZoomRecyclerView scrollList = activity.findViewById(R.id.scroll_list);
            TestSupport.waitUntil(() -> maxRenderedWidth.get() > 0, 5000, "initial 1x render");
            int baseWidth = maxRenderedWidth.get();

            // Scroll to the seam between PDF Page 0 and Page 1, then pinch-zoom.
            scenario.onActivity(a -> {
                scrollList.scrollBy(0, 600);
                TestSupport.layout(a);
                pinchAt(scrollList, 411f, 500f, 60f, 760f);
            });
            TestSupport.waitUntil(() -> maxRenderedWidth.get() > baseWidth, 5000,
                    "high-DPI PDF re-render triggered by scroll viewport zoom");

            assertTrue(scrollList.isZoomed());
            assertTrue("zoomed PDF scroll requested sharper render (" + maxRenderedWidth.get()
                    + " > " + baseWidth + ")", maxRenderedWidth.get() >= baseWidth * 2);
        }
    }

    @Test
    public void epubPagesModePinchZoomsWholeContentsPageAndTapResets() throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        prefs.viewMode = "pages";
        prefs.save();

        Book book = installEpubWithShortContents("Pages Mode Contents Book");
        try (ActivityScenario<ReaderActivity> scenario = open(book)) {
            ReaderActivity activity = TestSupport.currentActivity(scenario);
            ViewPager2 pager = activity.findViewById(R.id.pager);
            assertEquals(View.VISIBLE, pager.getVisibility());

            RecyclerView pagerRv = (RecyclerView) pager.getChildAt(0);
            PageView pv = (PageView) pagerRv.getChildAt(0);
            assertNotNull(pv);

            scenario.onActivity(a -> pinchAt(pager, 411f, 350f, 60f, 760f));
            TestSupport.idle();
            assertTrue("Pages mode Contents PageView zooms as a whole screen", pv.isZoomed());

            scenario.onActivity(a -> tapActivity(a, 100f, 700f));
            TestSupport.idle();
            assertFalse("single tap resets Pages mode zoom", pv.isZoomed());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ActivityScenario<ReaderActivity> open(Book book) {
        Intent intent = new Intent(app, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityScenario<ReaderActivity> scenario = ActivityScenario.launch(intent);
        scenario.onActivity(TestSupport::layout);
        ReaderActivity activity = TestSupport.currentActivity(scenario);
        TestSupport.waitUntil(() -> activity.getSession() != null, 15000,
                "session never opened");
        scenario.onActivity(TestSupport::layout);
        TestSupport.idle();
        return scenario;
    }

    private Book installEpubWithShortContents(String title) throws Exception {
        File dir = new File(app.getFilesDir(), "books");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File epubFile = new File(dir, title.replace(' ', '_') + ".epub");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            putEntry(zos, "mimetype", "application/epub+zip");
            putEntry(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\"?>\n"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                    + "  <rootfiles><rootfile full-path=\"OEBPS/content.opf\""
                    + " media-type=\"application/oebps-package+xml\"/></rootfiles>\n"
                    + "</container>");
            putEntry(zos, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n"
                    + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                    + "    <dc:identifier id=\"id\">urn:uuid:12345</dc:identifier>\n"
                    + "    <dc:title>" + title + "</dc:title>\n"
                    + "    <dc:creator>Test Author</dc:creator>\n"
                    + "    <dc:language>en</dc:language>\n"
                    + "  </metadata>\n"
                    + "  <manifest>\n"
                    + "    <item id=\"toc\" href=\"contents.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n"
                    + "    <item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                    + "    <item id=\"c2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                    + "  </manifest>\n"
                    + "  <spine>\n"
                    + "    <itemref idref=\"toc\"/>\n"
                    + "    <itemref idref=\"c1\"/>\n"
                    + "    <itemref idref=\"c2\"/>\n"
                    + "  </spine>\n"
                    + "</package>");
            putEntry(zos, "OEBPS/contents.xhtml",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n"
                    + "<body>\n"
                    + "  <nav epub:type=\"toc\"><h1>Contents</h1>\n"
                    + "    <ol>\n"
                    + "      <li><a href=\"ch1.xhtml\">1. Chapter One — Morning</a></li>\n"
                    + "      <li><a href=\"ch2.xhtml\">2. Chapter Two — Evening</a></li>\n"
                    + "    </ol>\n"
                    + "  </nav>\n"
                    + "</body></html>");
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= 18; i++) {
                body.append("<p>Paragraph ").append(i)
                        .append(" flows continuously down the page so the reader can inspect ")
                        .append("both the short Contents page and the following chapter on the ")
                        .append("same scrollable viewport without split-box clipping.</p>\n");
            }
            putEntry(zos, "OEBPS/ch1.xhtml",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>\n"
                    + "<h1>Chapter One — Morning</h1>\n" + body + "</body></html>");
            putEntry(zos, "OEBPS/ch2.xhtml",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>\n"
                    + "<h1>Chapter Two — Evening</h1>\n" + body + "</body></html>");
        }
        Book book = FolderScanner.describe(app, Uri.fromFile(epubFile).toString(),
                epubFile.getName(), epubFile.length(), epubFile.lastModified());
        List<Book> books = Library.books(app);
        books.add(0, book);
        Library.saveBooks(app, books);
        return book;
    }

    private static void putEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static Bitmap renderView(View view) {
        Bitmap bmp = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas);
        return bmp;
    }

    private static int countInkPixels(Bitmap bmp, int yStart, int yEnd) {
        int count = 0;
        int w = bmp.getWidth();
        for (int y = Math.max(0, yStart); y < Math.min(bmp.getHeight(), yEnd); y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c = bmp.getPixel(x, y);
                if (Color.red(c) < 200 || Color.green(c) < 200 || Color.blue(c) < 200) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean regionsIdentical(Bitmap a, Bitmap b, int yStart, int yEnd) {
        int w = Math.min(a.getWidth(), b.getWidth());
        for (int y = Math.max(0, yStart); y < Math.min(a.getHeight(), yEnd); y += 2) {
            for (int x = 0; x < w; x += 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
            }
        }
        return true;
    }

    private static void pinchAt(View target, float cx, float cy, float startSpan, float endSpan) {
        long t = SystemClock.uptimeMillis();
        dispatchTouch(target, t, MotionEvent.ACTION_DOWN, cx - startSpan / 2f, cy);
        dispatchTwoFinger(target, t + 10, MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - startSpan / 2f, cy, cx + startSpan / 2f, cy);
        for (int step = 1; step <= 6; step++) {
            float span = startSpan + (endSpan - startSpan) * (step / 6f);
            dispatchTwoFinger(target, t + 10 + step * 16L, MotionEvent.ACTION_MOVE,
                    cx - span / 2f, cy, cx + span / 2f, cy);
        }
        dispatchTwoFinger(target, t + 120, MotionEvent.ACTION_POINTER_UP
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - endSpan / 2f, cy, cx + endSpan / 2f, cy);
        dispatchTouch(target, t + 136, MotionEvent.ACTION_UP, cx - endSpan / 2f, cy);
    }

    private static void dragOneFinger(View target, float x0, float y0, float x1, float y1) {
        long t = SystemClock.uptimeMillis();
        dispatchTouch(target, t, MotionEvent.ACTION_DOWN, x0, y0);
        for (int step = 1; step <= 5; step++) {
            float u = step / 5f;
            dispatchTouch(target, t + step * 16L, MotionEvent.ACTION_MOVE,
                    x0 + (x1 - x0) * u, y0 + (y1 - y0) * u);
        }
        dispatchTouch(target, t + 100, MotionEvent.ACTION_UP, x1, y1);
    }

    private static void tapActivity(ReaderActivity activity, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        activity.dispatchTouchEvent(d);
        activity.dispatchTouchEvent(u);
        d.recycle();
        u.recycle();
    }

    private static void dispatchTouch(View target, long time, int action, float x, float y) {
        MotionEvent.PointerProperties p0 = new MotionEvent.PointerProperties();
        p0.id = 0;
        p0.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c0 = new MotionEvent.PointerCoords();
        c0.x = x;
        c0.y = y;
        c0.pressure = 1f;
        MotionEvent ev = MotionEvent.obtain(time, time, action, 1,
                new MotionEvent.PointerProperties[]{p0},
                new MotionEvent.PointerCoords[]{c0},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        target.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private static void dispatchTwoFinger(View target, long time, int action,
                                          float x0, float y0, float x1, float y1) {
        MotionEvent.PointerProperties p0 = new MotionEvent.PointerProperties();
        p0.id = 0;
        p0.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties p1 = new MotionEvent.PointerProperties();
        p1.id = 1;
        p1.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c0 = new MotionEvent.PointerCoords();
        c0.x = x0;
        c0.y = y0;
        c0.pressure = 1f;
        MotionEvent.PointerCoords c1 = new MotionEvent.PointerCoords();
        c1.x = x1;
        c1.y = y1;
        c1.pressure = 1f;
        MotionEvent ev = MotionEvent.obtain(time, time, action, 2,
                new MotionEvent.PointerProperties[]{p0, p1},
                new MotionEvent.PointerCoords[]{c0, c1},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        target.dispatchTouchEvent(ev);
        ev.recycle();
    }
}
