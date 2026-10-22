package com.aera.read.io;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.aera.read.data.Book;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Opens book content straight from its location; nothing is copied into app storage. */
public final class BookFiles {

    private BookFiles() {
    }

    public static SeekableSource openSeekable(Context ctx, Book book) throws IOException {
        return openSeekable(ctx, book.uri);
    }

    public static SeekableSource openSeekable(Context ctx, String uriString) throws IOException {
        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new FileNotFoundException(uriString);
            return new FileSource(new File(path));
        }
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Cannot open descriptor");
            return new FdSource(pfd);
        } catch (Exception e) {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
            // Provider streams are sometimes pipes: spool to the cache once.
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new FileNotFoundException(uriString);
            return new CachingSource(in, new File(ctx.getCacheDir(), "open"));
        }
    }

    public static ParcelFileDescriptor openDescriptor(Context ctx, Book book) throws IOException {
        return openDescriptor(ctx, book.uri);
    }

    public static ParcelFileDescriptor openDescriptor(Context ctx, String uriString)
            throws IOException {
        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new FileNotFoundException(uriString);
            return ParcelFileDescriptor.open(new File(path),
                    ParcelFileDescriptor.MODE_READ_ONLY);
        }
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new FileNotFoundException(uriString);
        return pfd;
    }

    public static String extensionOf(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }
}
