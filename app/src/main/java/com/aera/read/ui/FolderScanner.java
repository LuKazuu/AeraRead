package com.aera.read.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import com.aera.read.data.Book;
import com.aera.read.data.Library;
import com.aera.read.epub.EpubMetadata;
import com.aera.read.epub.EpubParser;
import com.aera.read.io.BookFiles;
import com.aera.read.io.SeekableSource;
import com.aera.read.io.ZipArchive;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Recursively scans user-granted folders. Books are read in place and never copied. */
public final class FolderScanner {

    public interface Listener {
        void onProgress(int scanned, int total);
    }

    public static final class Result {
        public final List<Book> books = new ArrayList<>();
        public int added;
        public int removed;
        public int failed;
        public int unreadableFolders;
    }

    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 8000;

    private FolderScanner() {
    }

    private static final class Doc {
        final String uri;
        final String name;
        final long size;
        final long lastModified;

        Doc(String uri, String name, long size, long lastModified) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /** Reads metadata (and a cached thumbnail) for a single document. */
    public static Book describe(Context ctx, String uri, String displayName, long size,
                                long lastModified) {
        return buildBook(ctx, new Doc(uri, displayName, size, lastModified), new Result());
    }

    public static Result scan(Context ctx, List<String> folders, List<Book> existing,
                              Listener listener) {
        Result result = new Result();
        List<Doc> docs = new ArrayList<>();
        for (String folder : folders) {
            try {
                Uri tree = Uri.parse(folder);
                if (!walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree),
                        docs, new HashSet<>(), 0)) {
                    result.unreadableFolders++;
                }
            } catch (Exception e) {
                result.unreadableFolders++;
            }
        }

        Map<String, Book> previous = new HashMap<>();
        for (Book b : existing) previous.put(b.uri, b);

        int scanned = 0;
        for (Doc doc : docs) {
            if (listener != null) listener.onProgress(scanned, docs.size());
            scanned++;
            Book known = previous.remove(doc.uri);
            boolean reuse = known != null && known.size == doc.size
                    && known.lastModified == doc.lastModified
                    && (known.coverFile.isEmpty() || new File(known.coverFile).exists());
            Book book = reuse ? known : buildBook(ctx, doc, result);
            if (book != null) {
                if (!reuse) result.added++;
                result.books.add(book);
            }
        }

        // Anything left in previous disappeared from the folders.
        for (Book gone : previous.values()) {
            Library.deleteQuietly(gone.coverFile);
            result.removed++;
        }
        result.books.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        return result;
    }

    /** @return false when the folder itself could not be listed. */
    private static boolean walk(Context ctx, Uri tree, String parentId, List<Doc> out,
                                Set<String> visited, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES || !visited.add(parentId)) return true;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        ContentResolver resolver = ctx.getContentResolver();
        try (Cursor cursor = resolver.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        }, null, null, null)) {
            if (cursor == null) return false;
            while (cursor.moveToNext() && out.size() < MAX_FILES) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                if (id == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // a broken subtree is skipped; the folder as a whole still counts as read
                    walk(ctx, tree, id, out, visited, depth + 1);
                    continue;
                }
                String ext = BookFiles.extensionOf(name);
                if (!ext.equals(".epub") && !ext.equals(".pdf")) continue;
                if (name.startsWith(".")) continue;
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                out.add(new Doc(docUri.toString(), name, size, modified));
            }
            return true;
        } catch (Exception e) {
            // a single unreadable subtree should not abort the scan
            return false;
        }
    }

    private static Book buildBook(Context ctx, Doc doc, Result result) {
        Book book = new Book();
        book.uri = doc.uri;
        book.size = doc.size;
        book.lastModified = doc.lastModified;
        book.addedAt = System.currentTimeMillis();
        String fallbackTitle = titleFromName(doc.name);
        book.title = fallbackTitle;
        book.type = doc.name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? Book.TYPE_PDF : Book.TYPE_EPUB;

        if (book.isPdf()) {
            Bitmap cover = pdfCover(ctx, doc.uri);
            if (cover != null) {
                book.coverFile = Thumbnails.save(ctx, doc.uri, cover);
                cover.recycle();
            }
            return book;
        }

        SeekableSource source = null;
        ZipArchive zip = null;
        try {
            source = BookFiles.openSeekable(ctx, book);
            zip = ZipArchive.open(source);
            EpubMetadata meta = new EpubParser(zip).readMetadata(fallbackTitle);
            if (!meta.title.isEmpty()) book.title = meta.title;
            book.author = meta.author;
            if (meta.cover != null && meta.cover.length > 0) {
                Bitmap bitmap = decode(meta.cover);
                if (bitmap != null) {
                    book.coverFile = Thumbnails.save(ctx, doc.uri, bitmap);
                    bitmap.recycle();
                }
            }
            return book;
        } catch (Exception e) {
            result.failed++;
            return book; // still listed; the reader reports the problem if it cannot open it
        } finally {
            try {
                if (zip != null) zip.close();
                else if (source != null) source.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap decode(byte[] data) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) return null;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, bounds.outWidth / 700);
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap pdfCover(Context ctx, String uriString) {
        try (ParcelFileDescriptor pfd = BookFiles.openDescriptor(ctx, uriString);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() <= 0) return null;
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                int width = 360;
                int height = Math.max(1, Math.round(width * page.getHeight()
                        / (float) Math.max(1, page.getWidth())));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static String titleFromName(String name) {
        String title = name.replaceAll("\\.[^.]+$", "").replace('_', ' ').trim();
        return title.isEmpty() ? name : title;
    }
}
