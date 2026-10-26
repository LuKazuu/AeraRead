package com.aera.read.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Minimal read-only ZIP reader over a {@link SeekableSource}. Parses the central directory so
 * entries can be read in any order, including books that live in a content provider and are
 * never copied to app storage.
 */
public final class ZipArchive implements Closeable {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int EOCD64_SIG = 0x06064b50;
    private static final int EOCD64_LOCATOR_SIG = 0x07064b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;
    private static final int MAX_ENTRY_BYTES = 96 * 1024 * 1024;

    public static final class Entry {
        public final String name;
        final int method;
        final long compressedSize;
        final long size;
        final long localOffset;

        Entry(String name, int method, long compressedSize, long size, long localOffset) {
            this.name = name;
            this.method = method;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localOffset = localOffset;
        }
    }

    private final SeekableSource source;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, String> canonicalNames = new HashMap<>();
    private boolean closed;

    private ZipArchive(SeekableSource source) {
        this.source = source;
    }

    public static ZipArchive open(SeekableSource source) throws IOException {
        ZipArchive zip = new ZipArchive(source);
        try {
            zip.readCentralDirectory();
            return zip;
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e instanceof IOException ? (IOException) e : new IOException("Bad zip", e);
        }
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) out.add(e.name);
        Collections.sort(out);
        return out;
    }

    public boolean contains(String name) {
        return resolve(name) != null;
    }

    public long sizeOf(String name) {
        Entry e = resolve(name);
        return e == null ? -1 : e.size;
    }

    public byte[] read(String name) throws IOException {
        Entry entry = resolve(name);
        if (entry == null) return null;
        if (entry.size > MAX_ENTRY_BYTES) throw new IOException("Entry too large: " + name);
        if (entry.size == 0) return new byte[0];
        try (InputStream in = open(entry)) {
            if (entry.size > 0 && entry.size <= MAX_ENTRY_BYTES) {
                int expected = (int) entry.size;
                byte[] exact = new byte[expected];
                int pos = 0;
                int n;
                while (pos < expected && (n = in.read(exact, pos, expected - pos)) > 0) {
                    pos += n;
                }
                int extra = in.read();
                if (extra < 0 && pos == expected) return exact;
                ByteArrayOutputStream out = new ByteArrayOutputStream(expected + 1024);
                out.write(exact, 0, pos);
                if (extra >= 0) {
                    out.write(extra);
                    byte[] buffer = new byte[16 * 1024];
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    public InputStream open(String name) throws IOException {
        Entry entry = resolve(name);
        if (entry == null) throw new IOException("Missing entry: " + name);
        return open(entry);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        source.close();
    }

    // ------------------------------------------------------------------ internals

    private Entry resolve(String name) {
        Entry exact = entries.get(name);
        if (exact != null) return exact;
        String real = canonicalNames.get(name.toLowerCase(Locale.ROOT));
        return real == null ? null : entries.get(real);
    }

    private static final class SegmentStream extends InputStream {
        private final SeekableSource source;
        private long position;
        private long remaining;
        private final byte[] buffer;
        private int bufferPos;
        private int bufferLen;

        SegmentStream(SeekableSource source, long start, long length) {
            this.source = source;
            this.position = start;
            this.remaining = length;
            this.buffer = new byte[(int) Math.max(256, Math.min(32 * 1024L, length))];
        }

        @Override
        public int read() throws IOException {
            if (bufferPos >= bufferLen) {
                if (remaining <= 0) return -1;
                int want = (int) Math.min(buffer.length, remaining);
                int n = source.readAt(position, buffer, 0, want);
                if (n <= 0) return -1;
                bufferPos = 0;
                bufferLen = n;
                position += n;
                remaining -= n;
            }
            return buffer[bufferPos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (bufferPos >= bufferLen) {
                if (remaining <= 0) return -1;
                int want = (int) Math.min(buffer.length, remaining);
                int n = source.readAt(position, buffer, 0, want);
                if (n <= 0) return -1;
                bufferPos = 0;
                bufferLen = n;
                position += n;
                remaining -= n;
            }
            int copy = Math.min(len, bufferLen - bufferPos);
            System.arraycopy(buffer, bufferPos, b, off, copy);
            bufferPos += copy;
            return copy;
        }

        @Override
        public long skip(long n) {
            long skipped = 0;
            int buffered = bufferLen - bufferPos;
            if (buffered > 0) {
                int take = (int) Math.min(buffered, n);
                bufferPos += take;
                skipped += take;
            }
            long rest = Math.min(n - skipped, remaining);
            if (rest > 0) {
                position += rest;
                remaining -= rest;
                bufferPos = 0;
                bufferLen = 0;
                skipped += rest;
            }
            return skipped;
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, bufferLen - bufferPos + remaining);
        }
    }

    private InputStream open(Entry entry) throws IOException {
        byte[] header = readFully(entry.localOffset, 30);
        if (u32(header, 0) != LOC_SIG) throw new IOException("Bad local header: " + entry.name);
        int nameLen = u16(header, 26);
        int extraLen = u16(header, 28);
        long dataStart = entry.localOffset + 30 + nameLen + extraLen;
        InputStream raw = new SegmentStream(source, dataStart, entry.compressedSize);
        if (entry.method == 0) return raw;
        if (entry.method == 8) return new RawInflateStream(raw);
        throw new IOException("Unsupported compression: " + entry.method);
    }

    private void readCentralDirectory() throws IOException {
        long size = source.size();
        if (size < 22) throw new IOException("Not a zip file");
        int tailLen = (int) Math.min(size, 22 + 0xFFFF);
        byte[] tail = readFully(size - tailLen, tailLen);
        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (u32(tail, i) == EOCD_SIG) {
                int commentLen = u16(tail, i + 20);
                if (i + 22 + commentLen == tailLen) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) throw new IOException("Missing zip directory");

        long entryCount = u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);
        long cdEnd = size - tailLen + eocd;

        boolean zip64 = entryCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL;
        if (zip64) {
            long locatorOffset = cdEnd - 20;
            if (locatorOffset >= 0) {
                byte[] locator = readFully(locatorOffset, 20);
                if (u32(locator, 0) == EOCD64_LOCATOR_SIG) {
                    long eocd64Offset = u64(locator, 8);
                    byte[] eocd64 = readFully(eocd64Offset, 56);
                    if (u32(eocd64, 0) != EOCD64_SIG) throw new IOException("Bad zip64 end");
                    entryCount = u64(eocd64, 32);
                    cdSize = u64(eocd64, 40);
                    cdOffset = u64(eocd64, 48);
                }
            }
        }
        if (cdOffset + cdSize > size) throw new IOException("Corrupt zip directory");

        byte[] cd = readFully(cdOffset, (int) cdSize);
        int p = 0;
        for (long i = 0; i < entryCount; i++) {
            if (p + 46 > cd.length || u32(cd, p) != CEN_SIG) {
                throw new IOException("Corrupt central directory");
            }
            int method = u16(cd, p + 10);
            long compressed = u32(cd, p + 20);
            long uncompressed = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28);
            int extraLen = u16(cd, p + 30);
            int commentLen = u16(cd, p + 32);
            long localOffset = u32(cd, p + 42);
            String name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
            int extraStart = p + 46 + nameLen;

            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL) {
                long[] zip64Values = parseZip64Extra(cd, extraStart, extraLen,
                        uncompressed == 0xFFFFFFFFL, compressed == 0xFFFFFFFFL,
                        localOffset == 0xFFFFFFFFL);
                uncompressed = zip64Values[0];
                compressed = zip64Values[1];
                localOffset = zip64Values[2];
            }

            if (!name.endsWith("/")) {
                Entry entry = new Entry(name, method, compressed, uncompressed, localOffset);
                entries.put(name, entry);
                canonicalNames.put(name.toLowerCase(Locale.ROOT), name);
            }
            p = extraStart + extraLen + commentLen;
            if (p > cd.length) throw new IOException("Corrupt central directory");
        }
        if (entries.isEmpty()) throw new IOException("Empty zip");
    }

    private static long[] parseZip64Extra(byte[] cd, int start, int length, boolean needSize,
                                          boolean needCompressed, boolean needOffset)
            throws IOException {
        long[] out = new long[]{-1, -1, -1};
        int p = start;
        int end = start + length;
        while (p + 4 <= end) {
            int id = u16(cd, p);
            int len = u16(cd, p + 2);
            if (id == 0x0001) {
                int q = p + 4;
                if (needSize && q + 8 <= end) {
                    out[0] = u64(cd, q);
                    q += 8;
                }
                if (needCompressed && q + 8 <= end) {
                    out[1] = u64(cd, q);
                    q += 8;
                }
                if (needOffset && q + 8 <= end) {
                    out[2] = u64(cd, q);
                }
                break;
            }
            p += 4 + len;
        }
        for (long v : out) {
            if (v == -1) return out;
        }
        if (out[0] == -1 || out[1] == -1 || out[2] == -1) {
            throw new IOException("Incomplete zip64 entry");
        }
        return out;
    }

    private byte[] readFully(long position, int length) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = source.readAt(position + read, out, read, length - read);
            if (n <= 0) throw new EOFException("Unexpected end of file");
            read += n;
        }
        return out;
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16)
                | ((b[p + 3] & 0xFFL) << 24);
    }

    static long u64(byte[] b, int p) {
        return u32(b, p) | (u32(b, p + 4) << 32);
    }

    /** Inflates a raw deflate stream and releases native resources on close. */
    private static final class RawInflateStream extends InflaterInputStream {
        private final Inflater inflaterRef;
        private boolean eofPadded;

        RawInflateStream(InputStream in) {
            this(in, new Inflater(true));
        }

        private RawInflateStream(InputStream in, Inflater inflater) {
            super(in, inflater, 32 * 1024);
            this.inflaterRef = inflater;
        }

        @Override
        protected void fill() throws IOException {
            if (closedStream()) throw new IOException("Stream closed");
            len = in.read(buf, 0, buf.length);
            if (len == -1) {
                if (eofPadded) throw new EOFException("Unexpected end of ZLIB input stream");
                eofPadded = true;
                buf[0] = 0;
                len = 1;
            }
            inf.setInput(buf, 0, len);
        }

        private boolean closedStream() {
            return in == null;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                inflaterRef.end();
            }
        }
    }
}
