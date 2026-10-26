package com.aera.read.io;

import android.os.ParcelFileDescriptor;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Lock-free positional {@link SeekableSource} over a content-resolver file descriptor.
 * Shares {@link FileSource#readChannel} for single-syscall {@code pread} random access.
 */
public final class FdSource implements SeekableSource {

    private final ParcelFileDescriptor pfd;
    private final FileInputStream stream;
    private final FileChannel channel;
    private volatile boolean closed;

    public FdSource(ParcelFileDescriptor pfd) throws IOException {
        this.pfd = pfd;
        this.stream = new FileInputStream(pfd.getFileDescriptor());
        this.channel = stream.getChannel();
        // Verify seekability upfront so callers fall back to CachingSource on pipe FDs.
        this.channel.position(0);
    }

    @Override
    public long size() throws IOException {
        if (closed) throw new EOFException("Source closed");
        return channel.size();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new EOFException("Source closed");
        return FileSource.readChannel(channel, position, buffer, offset, length);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        FileSource.closeQuietly(channel);
        FileSource.closeQuietly(stream);
        FileSource.closeQuietly(pfd);
    }
}
