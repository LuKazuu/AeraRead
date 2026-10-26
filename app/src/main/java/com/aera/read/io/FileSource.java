package com.aera.read.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Lock-free positional {@link SeekableSource} over a {@link File} via {@link FileChannel}.
 * Positional {@code FileChannel.read(ByteBuffer, long)} maps to {@code pread64} with no seek
 * syscall and no shared file-offset lock contention across reader and image-decode threads.
 */
public final class FileSource implements SeekableSource {

    private final FileChannel channel;
    private volatile boolean closed;

    public FileSource(File file) throws IOException {
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
    }

    @Override
    public long size() throws IOException {
        if (closed) throw new EOFException("Source closed");
        return channel.size();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new EOFException("Source closed");
        return readChannel(channel, position, buffer, offset, length);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        channel.close();
    }

    static int readChannel(FileChannel channel, long position, byte[] buffer, int offset,
                           int length) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buffer, offset, length);
        int total = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, position + total);
            if (n <= 0) break;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
