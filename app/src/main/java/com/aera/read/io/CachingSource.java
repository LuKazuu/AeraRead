package com.aera.read.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a non-seekable stream into a temporary cache file once, then reads with random
 * access. Deleted on close; used only for providers that cannot seek.
 */
public final class CachingSource implements SeekableSource {

    private final File temp;
    private final FileSource delegate;

    public CachingSource(InputStream input, File cacheDir) throws IOException {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        temp = File.createTempFile("book", ".tmp", cacheDir);
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            delegate = new FileSource(temp);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        return delegate.readAt(position, buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }
}
