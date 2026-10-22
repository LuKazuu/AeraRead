package com.aera.read.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Minimal documents provider used to exercise the real Storage Access Framework code path:
 * tree child queries, document uris and file descriptors.
 */
public class TestDocumentsProvider extends ContentProvider {

    public static final String AUTHORITY = "aera.test.docs";

    private File root;

    public void setRoot(File root) {
        this.root = root;
    }

    public static Uri treeUri() {
        return Uri.parse("content://" + AUTHORITY + "/tree/root");
    }

    public void register(Context context) {
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.grantUriPermissions = true;
        info.exported = true;
        info.packageName = context.getPackageName();
        attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, this);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(String docId) {
        if (docId == null || docId.isEmpty() || "root".equals(docId)) return root;
        return new File(root, docId);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        List<String> segments = uri.getPathSegments();
        String[] columns = projection != null ? projection : new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        int count = segments.size();
        if (count >= 5 && "children".equals(segments.get(count - 1))) {
            File dir = resolve(docId(segments, count - 1));
            File[] children = dir.listFiles();
            if (children == null) return cursor;
            for (File child : children) {
                addRow(cursor, columns, child);
            }
            return cursor;
        }
        if (count >= 4) {
            File file = resolve(docId(segments, count));
            if (file.exists()) addRow(cursor, columns, file);
        }
        return cursor;
    }

    /** Joins the document id segments that follow /tree/{treeId}/document/. */
    private String docId(List<String> segments, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < endExclusive; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(segments.get(i));
        }
        return sb.toString();
    }

    private void addRow(MatrixCursor cursor, String[] columns, File file) {
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i];
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                row[i] = relative(file);
            } else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) {
                row[i] = file.getName();
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
                row[i] = file.isDirectory()
                        ? DocumentsContract.Document.MIME_TYPE_DIR : mimeOf(file.getName());
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) {
                row[i] = file.length();
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
                row[i] = file.lastModified();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
    }

    private String relative(File file) {
        String rootPath = root.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(rootPath)) return "root";
        return path.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private String mimeOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".epub")) return "application/epub+zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 4) throw new FileNotFoundException(uri.toString());
        File file = resolve(docId(segments, segments.size()));
        if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
