package com.aera.read.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Fonts downloaded by the user, kept as plain files in private storage. Nothing is bundled
 * with the app: a font exists here only after a download, and removing it deletes the file.
 */
public final class FontStore {

    public interface Listener {
        /** Called on the main thread while bytes arrive. */
        void onProgress(String id, int percent);

        /** Called on the main thread once, after success or failure. */
        void onFinished(String id, boolean ok);
    }

    /** Test seam for the network; production uses {@link HttpURLConnection}. */
    interface Downloader {
        Body open(String url) throws IOException;
    }

    static final class Body implements AutoCloseable {
        final InputStream stream;
        final long length;
        private final HttpURLConnection connection;

        Body(InputStream stream, long length) {
            this(stream, length, null);
        }

        Body(InputStream stream, long length, HttpURLConnection connection) {
            this.stream = stream;
            this.length = length;
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                stream.close();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static final java.util.concurrent.ThreadPoolExecutor IO = createPool();

    private static java.util.concurrent.ThreadPoolExecutor createPool() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                2, 2, 15L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "aera-font-dl");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> RUNNING = Collections.synchronizedSet(new HashSet<>());
    private static Downloader downloader = FontStore::openUrl;

    private FontStore() {
    }

    static void setDownloader(Downloader replacement) {
        downloader = replacement == null ? FontStore::openUrl : replacement;
    }

    public static File dir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "fonts");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        return dir;
    }

    public static File file(Context context, String id) {
        return new File(dir(context), id + ".ttf");
    }

    public static boolean isInstalled(Context context, String id) {
        return id != null && !Fonts.SYSTEM.equals(id) && FontCatalog.byId(id) != null
                && file(context, id).isFile();
    }

    public static boolean isDownloading(String id) {
        return RUNNING.contains(id);
    }

    /** Downloads one font. Ignored when the same font is already on its way. */
    public static void download(Context context, final FontCatalog.Entry entry,
                                final Listener listener) {
        if (entry == null || !RUNNING.add(entry.id)) return;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> listener.onProgress(entry.id, 0));
        IO.execute(() -> {
            boolean ok = fetch(app, entry, listener);
            RUNNING.remove(entry.id);
            Fonts.invalidate();
            MAIN.post(() -> listener.onFinished(entry.id, ok));
        });
    }

    private static boolean fetch(Context context, FontCatalog.Entry entry, Listener listener) {
        File target = file(context, entry.id);
        File part = new File(target.getPath() + ".part");
        try (Body body = downloader.open(entry.url)) {
            long total = body.length > 0 ? body.length : entry.bytes;
            long read = 0;
            int last = -1;
            try (FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = body.stream.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                    read += count;
                    int percent = (int) Math.min(99, read * 100 / Math.max(1, total));
                    if (percent != last) {
                        last = percent;
                        int shown = percent;
                        MAIN.post(() -> listener.onProgress(entry.id, shown));
                    }
                }
                out.getFD().sync();
            }
            if (body.length > 0 && read != body.length) throw new IOException("short read");
            if (!looksLikeFont(part)) throw new IOException("not a font");
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!part.renameTo(target)) throw new IOException("cannot store " + target);
            MAIN.post(() -> listener.onProgress(entry.id, 100));
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
    }

    /** Removes a downloaded font. True when a file was actually deleted. */
    public static boolean remove(Context context, String id) {
        if (id == null || Fonts.SYSTEM.equals(id)) return false;
        File file = file(context, id);
        //noinspection ResultOfMethodCallIgnored
        new File(file.getPath() + ".part").delete();
        boolean deleted = file.delete();
        Fonts.invalidate();
        return deleted;
    }

    /** Drops half-written files left behind by a download the system killed. */
    public static void cleanPartials(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".part") && !isDownloading(name.substring(0, name.length() - 5))) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static boolean looksLikeFont(File file) throws IOException {
        if (file.length() < 1024) return false;
        byte[] head = new byte[4];
        try (InputStream in = new java.io.FileInputStream(file)) {
            if (in.read(head) != 4) return false;
        }
        String ascii = new String(head, java.nio.charset.StandardCharsets.US_ASCII);
        return (head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0)
                || "true".equals(ascii) || "OTTO".equals(ascii) || "ttcf".equals(ascii);
    }

    private static Body openUrl(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/octet-stream");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            return new Body(connection.getInputStream(), connection.getContentLengthLong(),
                    connection);
        } catch (IOException | RuntimeException e) {
            connection.disconnect();
            throw e;
        }
    }
}
