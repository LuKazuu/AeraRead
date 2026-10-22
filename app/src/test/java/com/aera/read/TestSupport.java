package com.aera.read;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;

import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.ui.FolderScanner;

import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for Robolectric tests. */
public final class TestSupport {

    private TestSupport() {
    }

    public static InputStream sample(String name) {
        InputStream in = TestSupport.class.getResourceAsStream("/samples/" + name);
        if (in == null) throw new IllegalStateException("Missing sample " + name);
        return in;
    }

    /** Places a sample book in test storage and registers it like a folder scan would. */
    public static Book install(Context ctx, String sampleName) throws Exception {
        File file = copySample(ctx, sampleName);
        Book book = FolderScanner.describe(ctx, Uri.fromFile(file).toString(), sampleName,
                file.length(), file.lastModified());
        List<Book> books = Library.books(ctx);
        books.add(0, book);
        Library.saveBooks(ctx, books);
        return book;
    }

    public static File copySample(Context ctx, String sampleName) throws Exception {
        File dir = new File(ctx.getFilesDir(), "books");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, sampleName);
        try (InputStream in = sample(sampleName);
             FileOutputStream out = new FileOutputStream(dest)) {
            in.transferTo(out);
        }
        return dest;
    }

    /** Puts one of the test font files where a completed download would leave it. */
    public static File installFont(Context ctx, String id) throws Exception {
        File dir = new File(ctx.getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, id + ".ttf");
        try (InputStream in = TestSupport.class.getResourceAsStream("/testfonts/" + id + "-test.ttf");
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("Missing test font " + id);
            in.transferTo(out);
        }
        com.aera.read.util.Fonts.invalidate();
        return dest;
    }

    public static byte[] readFont(String id) throws Exception {
        try (InputStream in = TestSupport.class.getResourceAsStream("/testfonts/" + id + "-test.ttf")) {
            if (in == null) throw new IllegalStateException("Missing test font " + id);
            return in.readAllBytes();
        }
    }

    /** A PDF record whose renderer is stubbed out by the test. */
    public static Book installFakePdf(Context ctx, String title) throws Exception {
        File dir = new File(ctx.getFilesDir(), "books");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, title.replace(' ', '_') + ".pdf");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("%PDF-1.4\n% test stub\n".getBytes());
        }
        Book book = new Book();
        book.uri = Uri.fromFile(file).toString();
        book.title = title;
        book.author = "Test Author";
        book.type = Book.TYPE_PDF;
        book.size = file.length();
        book.lastModified = file.lastModified();
        book.addedAt = System.currentTimeMillis();
        List<Book> books = Library.books(ctx);
        books.add(0, book);
        Library.saveBooks(ctx, books);
        return book;
    }

    /**
     * Builds a one-chapter EPUB whose cover PNG has transparent margins, like many
     * real covers. Returns the file; callers register it with {@link #install}.
     */
    public static File writeEpubWithCover(Context ctx, String title, int coverW, int coverH,
                                          int artLeft, int artTop, int artRight, int artBottom,
                                          int artColor) throws Exception {
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(coverW, coverH,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(cover);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(artColor);
        canvas.drawRect(artLeft, artTop, artRight, artBottom, paint);
        File png = new File(ctx.getCacheDir(), "cover-fixture.png");
        try (FileOutputStream out = new FileOutputStream(png)) {
            cover.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        File epub = File.createTempFile("fixture", ".epub");
        try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(epub))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/container.xml"));
            zip.write(("<?xml version=\"1.0\"?><container version=\"1.0\" "
                    + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                    + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                    .getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/content.opf"));
            zip.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" "
                    + "unique-identifier=\"id\"><metadata "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:title>" + title + "</dc:title>"
                    + "<dc:identifier id=\"id\">x</dc:identifier>"
                    + "<dc:language>en</dc:language>"
                    + "<meta name=\"cover\" content=\"cover-image\"/>"
                    + "</metadata><manifest>"
                    + "<item id=\"cover-image\" href=\"cover.png\" media-type=\"image/png\"/>"
                    + "<item id=\"c1\" href=\"ch1.xhtml\" "
                    + "media-type=\"application/xhtml+xml\"/>"
                    + "</manifest><spine><itemref idref=\"c1\"/></spine></package>")
                    .getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>One</title>"
                    + "</head><body><h1>One</h1><p>" + "word ".repeat(300)
                    + "</p></body></html>").getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/cover.png"));
            zip.write(java.nio.file.Files.readAllBytes(png.toPath()));
            zip.closeEntry();
        }
        return epub;
    }


    /** Tiny EPUB with Chinese, Japanese and Korean text, for CJK font coverage. */
    public static File writeEpubWithCjkText(Context ctx) throws Exception {
        String body = "漢字の本を読む。日本語の文章です。中文书籍。这是一段中文文本。한국어 책입니다. 한글 문장입니다。";
        String dialog = "<p>「日本語の文章です。」</p><p>「这是一段中文文本。」</p><p>「한국어 책입니다.」</p>";
        String latin = "The same page also carries Latin text so both scripts are on screen. ";
        File epub = File.createTempFile("cjk", ".epub");
        try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(epub))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/container.xml"));
            zip.write(("<?xml version=\"1.0\"?><container version=\"1.0\" "
                    + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                    + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                    .getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/content.opf"));
            zip.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" "
                    + "unique-identifier=\"id\"><metadata "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:title>CJK Sample</dc:title><dc:creator>Test</dc:creator>"
                    + "<dc:identifier id=\"id\">z</dc:identifier>"
                    + "<dc:language>zh</dc:language></metadata><manifest>"
                    + "<item id=\"c1\" href=\"ch1.xhtml\" "
                    + "media-type=\"application/xhtml+xml\"/></manifest>"
                    + "<spine><itemref idref=\"c1\"/></spine></package>").getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>CJK</title>"
                    + "</head><body><h1>漢字の本</h1><p>" + body.repeat(3) + "</p>"
                    + dialog
                    + "<p>" + latin.repeat(3) + "</p>"
                    + "<p>" + body.repeat(4) + "</p>"
                    + dialog
                    + "<p>" + body.repeat(4) + "</p></body></html>").getBytes());
            zip.closeEntry();
        }
        return epub;
    }

    /** Registers an existing EPUB file as a library book, as a folder scan would. */
    public static Book installFile(Context ctx, File file, String displayName) {
        Book book = FolderScanner.describe(ctx, Uri.fromFile(file).toString(), displayName,
                file.length(), file.lastModified());
        List<Book> books = Library.books(ctx);
        books.add(0, book);
        Library.saveBooks(ctx, books);
        return book;
    }

    public interface Condition {
        boolean check();
    }

    public static void waitUntil(Condition condition, long timeoutMs, String message) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            idle();
            if (condition.check()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        idle();
        if (!condition.check()) throw new AssertionError(message);
    }

    public static <T extends android.app.Activity> T currentActivity(
            androidx.test.core.app.ActivityScenario<T> scenario) {
        final java.util.concurrent.atomic.AtomicReference<T> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        scenario.onActivity(ref::set);
        return ref.get();
    }

    /** Robolectric attaches windows without a real layout pass; this forces one. */
    public static void layout(android.app.Activity activity) {
        android.view.View decor = activity.getWindow().getDecorView();
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h,
                        android.view.View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
    }

    /** Runs pending main-thread work, including anything posted with a short delay. */
    public static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
    }

    public static File resourceFile(String name) throws Exception {
        File tmp = File.createTempFile("sample", name.substring(name.lastIndexOf('.')));
        try (InputStream in = sample(name); FileOutputStream out = new FileOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }

    public static void clearLibrary(Application app) {
        List<Book> books = new ArrayList<>(Library.books(app));
        for (Book b : books) Library.remove(app, b.id());
        for (String folder : new ArrayList<>(Library.folders(app))) {
            Library.removeFolder(app, folder);
        }
    }
}
