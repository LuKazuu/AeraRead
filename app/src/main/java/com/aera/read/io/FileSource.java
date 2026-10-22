package com.aera.read.io;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** {@link SeekableSource} over a regular file. */
public final class FileSource implements SeekableSource {

    private final RandomAccessFile file;
    private boolean closed;

    public FileSource(File file) throws IOException {
        this.file = new RandomAccessFile(file, "r");
    }

    @Override
    public synchronized long size() throws IOException {
        if (closed) throw new EOFException("Source closed");
        return file.length();
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length)
            throws IOException {
        if (closed) throw new EOFException("Source closed");
        file.seek(position);
        return file.read(buffer, offset, length);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        file.close();
    }
}
