package com.aera.read.io;

import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * {@link SeekableSource} over a content-resolver file descriptor. Only providers that expose
 * a seekable descriptor work here; callers fall back to {@link CachingSource} otherwise.
 */
public final class FdSource implements SeekableSource {

    private final ParcelFileDescriptor pfd;
    private final FileInputStream stream;
    private final FileChannel channel;
    private boolean closed;

    public FdSource(ParcelFileDescriptor pfd) throws IOException {
        this.pfd = pfd;
        this.stream = new FileInputStream(pfd.getFileDescriptor());
        this.channel = stream.getChannel();
        // verifies seekability up front so callers can fall back early
        this.channel.position(0);
    }

    @Override
    public synchronized long size() throws IOException {
        if (closed) throw new EOFException("Source closed");
        return channel.size();
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length)
            throws IOException {
        if (closed) throw new EOFException("Source closed");
        ByteBuffer bb = ByteBuffer.wrap(buffer, offset, length);
        int total = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, position + total);
            if (n <= 0) break;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        closeQuietly(channel);
        closeQuietly(stream);
        closeQuietly(pfd);
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
