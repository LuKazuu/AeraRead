package com.aera.read.pdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies strict mutual exclusion between {@link PdfRendererSource#render(int, int)} and
 * {@link PdfRendererSource#close()} so closing a PDF mid-render can never overlap native calls.
 */
@RunWith(RobolectricTestRunner.class)
public class PdfRendererConcurrencyTest {

    @Test
    public void renderAndCloseNeverOverlapAcrossThreads() throws Exception {
        AtomicReference<Throwable> violation = new AtomicReference<>();

        for (int round = 0; round < 25; round++) {
            final AtomicBoolean busy = new AtomicBoolean(false);
            final AtomicBoolean backendClosed = new AtomicBoolean(false);
            final CountDownLatch enteredRender = new CountDownLatch(1);

            PdfRendererSource src = new PdfRendererSource(null, new PdfRendererSource.Backend() {
                @Override
                public int pageCount() {
                    return 16;
                }

                @Override
                public void renderPage(int page, int width,
                                       PdfRendererSource.BitmapAllocator allocator,
                                       PdfRendererSource.BitmapConsumer consumer)
                        throws IOException {
                    if (!busy.compareAndSet(false, true)) {
                        violation.compareAndSet(null,
                                new AssertionError("Concurrent render overlap"));
                    }
                    try {
                        if (backendClosed.get()) {
                            violation.compareAndSet(null,
                                    new AssertionError("Rendered after backend closed"));
                        }
                        enteredRender.countDown();
                        try {
                            Thread.sleep(4);
                        } catch (InterruptedException ignored) {
                        }
                        Bitmap bmp = allocator.allocate(width, 1.4f);
                        consumer.accept(bmp);
                    } finally {
                        busy.set(false);
                    }
                }

                @Override
                public void close() {
                    if (!busy.compareAndSet(false, true)) {
                        violation.compareAndSet(null,
                                new AssertionError("close() overlapped active renderPage()"));
                    }
                    try {
                        backendClosed.set(true);
                    } finally {
                        busy.set(false);
                    }
                }
            });

            Thread worker = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    try {
                        Bitmap b = src.render(i, 3000); // also exercises MAX_WIDTH=2400 clamp
                        if (b != null) {
                            assertEquals(2400, b.getWidth());
                            b.recycle();
                        }
                    } catch (IOException expectedOnceClosed) {
                        break;
                    } catch (Throwable t) {
                        violation.compareAndSet(null, t);
                        break;
                    }
                }
            });
            worker.start();
            assertTrue(enteredRender.await(2, TimeUnit.SECONDS));
            src.close();
            worker.join(3000);

            assertNull("Thread-safety violation: " + violation.get(), violation.get());
            assertTrue(backendClosed.get());
            assertFalse(busy.get());
            assertEquals(0, src.pageCount());
        }
    }
}
