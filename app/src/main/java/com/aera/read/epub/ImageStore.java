package com.aera.read.epub;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.aera.read.io.ZipArchive;

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
 * Decodes and caches illustrations from the EPUB archive. Decoding never happens on the
 * thread that draws: a caller gets the best bitmap already in memory and asks for a finer
 * one, which arrives later and repaints the page.
 */
public class ImageStore {

    private static final int MAX_CACHE_BYTES = 24 * 1024 * 1024;
    private static final ThreadPoolExecutor DECODER = createDecoderPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
    private final LruCache<String, Bitmap> cache;
    /** Widths held in memory for each image, so {@link #best} is a cheap lookup. */
    private final Map<String, TreeSet<Integer>> widths = new HashMap<>();
    /** Jobs already queued, so a pinch never asks for the same decode twice. */
    private final Set<String> pending = Collections.newSetFromMap(new ConcurrentHashMap<>());
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
        Bitmap best = best(path, width);
        if (best != null && best.getWidth() >= width) return best;

        final String key = key(path, width);
        if (!pending.add(key)) return best;
        DECODER.execute(() -> {
            Runnable deferredClose = null;
            try {
                synchronized (ImageStore.this) {
                    if (closed) return;
                    activeDecodes++;
                }
                try {
                    Bitmap result = decode(path, width);
                    if (result != null) {
                        boolean notify = false;
                        synchronized (ImageStore.this) {
                            if (!closed) {
                                TreeSet<Integer> known = widths.get(path);
                                if (known == null) {
                                    known = new TreeSet<>();
                                    widths.put(path, known);
                                }
                                known.add(width);
                                cache.put(key, result);
                                notify = onReady != null;
                            }
                        }
                        if (notify) MAIN.post(onReady);
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
            } catch (RuntimeException ignored) {
            } finally {
                pending.remove(key);
                if (deferredClose != null) deferredClose.run();
            }
        });
        return best;
    }

    public synchronized void trimMemory(int level) {
        if (closed) return;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.evictAll();
            widths.clear();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.maxSize() / 2);
        }
    }

    public void close() {
        close(null);
    }

    /**
     * Evicts cached bitmaps immediately and runs {@code afterDecodes} once no background
     * decode is reading from the archive, avoiding mid-stream file descriptor races in Skia.
     */
    public void close(Runnable afterDecodes) {
        boolean runNow = false;
        synchronized (this) {
            if (!closed) {
                closed = true;
                pending.clear();
                cache.evictAll();
                widths.clear();
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
            try (InputStream in = zip.open(path)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            // Opaque JPEGs have no alpha channel: RGB_565 halves bitmap RAM with no box artifacts.
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
            try (InputStream in = zip.open(path)) {
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (OutOfMemoryError oom) {
            synchronized (this) {
                cache.evictAll();
                widths.clear();
            }
            BitmapFactory.Options fallback = new BitmapFactory.Options();
            fallback.inSampleSize = Math.max(2, sample * 2);
            fallback.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = zip.open(path)) {
                decoded = BitmapFactory.decodeStream(in, null, fallback);
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
                // Keep the already-decoded bitmap rather than crashing under low memory.
            }
        }
        return decoded;
    }
}
