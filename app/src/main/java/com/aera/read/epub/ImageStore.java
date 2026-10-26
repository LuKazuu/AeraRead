package com.aera.read.epub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.aera.read.io.ZipArchive;
import com.aera.read.util.Ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Decodes and caches illustrations and inline gaiji/icons from the EPUB archive or inline
 * {@code data:image/...} URIs. Decoding never happens on the UI draw thread.
 */
public class ImageStore {

    private static final int MAX_CACHE_BYTES = 24 * 1024 * 1024;
    private static final ThreadPoolExecutor DECODER = createDecoderPool();

    private static ThreadPoolExecutor createDecoderPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "aera-epub-img");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final ZipArchive zip;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final Map<String, TreeSet<Integer>> widths = new HashMap<>();
    private final Set<String> pending = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<String, Boolean> monochromeInk = new ConcurrentHashMap<>();
    private int activeDecodes;
    private Runnable onClosedIdle;
    private boolean closed;

    public ImageStore(ZipArchive zip) {
        this.zip = zip;
        long maxHeap = Runtime.getRuntime().maxMemory();
        int budget = (int) Math.max(2 * 1024 * 1024L,
                Math.min(MAX_CACHE_BYTES, maxHeap / 10));
        cache = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue,
                                        Bitmap newValue) {
                if (newValue == null) {
                    int at = key.lastIndexOf('@');
                    if (at > 0) {
                        String path = key.substring(0, at);
                        try {
                            int w = Integer.parseInt(key.substring(at + 1));
                            TreeSet<Integer> known = widths.get(path);
                            if (known != null) {
                                known.remove(w);
                                if (known.isEmpty()) widths.remove(path);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        };
    }

    /** Reads intrinsic image dimensions without decoding pixels (supports zip & data URIs). */
    public static int[] readBounds(ZipArchive zip, String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = openSource(zip, path)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            return new int[]{opts.outWidth, opts.outHeight};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static InputStream openSource(ZipArchive zip, String path) throws IOException {
        if (path.startsWith("data:")) {
            byte[] bytes = EpubParser.decodeDataUri(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
        return zip == null ? null : zip.open(path);
    }

    /** True if the decoded image is a transparent-background dark monochrome glyph (gaiji/eq). */
    public boolean isMonochromeInk(String path) {
        Boolean v = path == null ? null : monochromeInk.get(path);
        return v != null && v;
    }

    /**
     * The sharpest bitmap already in memory that is at least {@code minWidth} wide, or the
     * widest one below it. Never touches the disk, so it is safe inside onDraw.
     */
    public synchronized Bitmap best(String path, int minWidth) {
        if (closed || path == null) return null;
        TreeSet<Integer> known = widths.get(path);
        if (known == null) return null;
        Integer atOrAbove = known.ceiling(minWidth);
        if (atOrAbove != null) {
            Bitmap hit = cache.get(key(path, atOrAbove));
            if (hit != null) return hit;
        }
        Integer below = known.floor(minWidth - 1);
        while (below != null) {
            Bitmap hit = cache.get(key(path, below));
            if (hit != null) return hit;
            below = known.lower(below);
        }
        return null;
    }

    /**
     * Asks for a bitmap of this width. Whatever is already in memory is returned straight
     * away; {@code onReady} runs on the main thread when a sharper one has been decoded.
     */
    public Bitmap request(final String path, final int width, final Runnable onReady) {
        if (path == null) return null;
        synchronized (this) {
            if (closed) return null;
        }
        int targetW = Math.max(1, width);
        Bitmap best = best(path, targetW);
        if (best != null && best.getWidth() >= targetW) return best;

        final String key = key(path, targetW);
        if (!pending.add(key)) return best;
        DECODER.execute(() -> {
            Runnable deferredClose = null;
            try {
                synchronized (ImageStore.this) {
                    if (closed) return;
                    activeDecodes++;
                }
                try {
                    Bitmap result = decode(path, targetW);
                    if (result != null) {
                        if (!monochromeInk.containsKey(path)) {
                            monochromeInk.put(path, detectMonochromeInk(result));
                        }
                        boolean notify = false;
                        synchronized (ImageStore.this) {
                            if (!closed) {
                                TreeSet<Integer> known = widths.get(path);
                                if (known == null) {
                                    known = new TreeSet<>();
                                    widths.put(path, known);
                                }
                                known.add(targetW);
                                cache.put(key, result);
                                notify = onReady != null;
                            }
                        }
                        if (notify) main.post(onReady);
                    }
                } finally {
                    synchronized (ImageStore.this) {
                        activeDecodes--;
                        if (closed && activeDecodes == 0 && onClosedIdle != null) {
                            deferredClose = onClosedIdle;
                            onClosedIdle = null;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                pending.remove(key);
                if (deferredClose != null) deferredClose.run();
            }
        });
        return best;
    }

    private static boolean detectMonochromeInk(Bitmap bmp) {
        if (!bmp.hasAlpha() || bmp.getConfig() == Bitmap.Config.RGB_565) return false;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0 || (w > 512 && h > 256)) return false;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        boolean sawTransparent = false;
        boolean sawInk = false;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bmp.getPixel(x, y);
                int a = Color.alpha(c);
                if (a < 24) {
                    sawTransparent = true;
                    continue;
                }
                sawInk = true;
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max > 95 || (max - min) > 28) return false;
            }
        }
        return sawTransparent && sawInk;
    }

    public synchronized void trimMemory(int level) {
        if (closed) return;
        Ui.trimCache(cache, level);
        if (cache.size() == 0) widths.clear();
    }

    public void close() {
        close(null);
    }

    public void close(Runnable afterDecodes) {
        boolean runNow = false;
        synchronized (this) {
            if (!closed) {
                closed = true;
                pending.clear();
                cache.evictAll();
                widths.clear();
                monochromeInk.clear();
            }
            if (activeDecodes == 0) {
                runNow = afterDecodes != null;
            } else {
                onClosedIdle = afterDecodes;
            }
        }
        if (runNow) afterDecodes.run();
    }

    private static String key(String path, int width) {
        return path + "@" + width;
    }

    private Bitmap decode(String path, int targetWidth) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = openSource(zip, path)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            boolean jpeg = "image/jpeg".equalsIgnoreCase(bounds.outMimeType)
                    || "image/jpg".equalsIgnoreCase(bounds.outMimeType);
            return decodeSampled(path, sample, targetWidth,
                    jpeg ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Bitmap decodeSampled(String path, int sample, int targetWidth, Bitmap.Config config)
            throws IOException {
        Bitmap decoded = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = config;
            try (InputStream in = openSource(zip, path)) {
                if (in != null) decoded = BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (OutOfMemoryError oom) {
            synchronized (this) {
                cache.evictAll();
                widths.clear();
            }
            BitmapFactory.Options fallback = new BitmapFactory.Options();
            fallback.inSampleSize = Math.max(2, sample * 2);
            fallback.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = openSource(zip, path)) {
                if (in != null) decoded = BitmapFactory.decodeStream(in, null, fallback);
            } catch (OutOfMemoryError ignored) {
                return null;
            }
        }
        if (decoded == null) return null;
        if (decoded.getWidth() > targetWidth) {
            int width = targetWidth;
            int height = Math.max(1,
                    Math.round(decoded.getHeight() * (float) width / decoded.getWidth()));
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
                if (scaled != decoded) decoded.recycle();
                decoded = scaled;
            } catch (OutOfMemoryError ignored) {
            }
        }
        return decoded;
    }
}
