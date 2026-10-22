package com.aera.read.io;

import java.io.Closeable;
import java.io.IOException;

/** Random-access byte source. Backed by a file or by a seekable content-resolver FD. */
public interface SeekableSource extends Closeable {

    long size() throws IOException;

    /** Reads up to {@code length} bytes at {@code position}; returns -1 at EOF. */
    int readAt(long position, byte[] buffer, int offset, int length) throws IOException;
}
