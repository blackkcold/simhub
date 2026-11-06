package com.blackkcold.simhub.p2p;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PeerSendGateTest {
    @Test public void timedOutRequestCannotPublishWhenDispatchQueueResumes() throws Exception {
        ExecutorService dispatch = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        PeerSendGate gate = new PeerSendGate();
        AtomicInteger published = new AtomicInteger();
        try {
            dispatch.submit(() -> { entered.countDown(); resume.await(5, TimeUnit.SECONDS); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> queued = dispatch.submit(() -> gate.send(published::incrementAndGet));
            // The caller's timeout cleanup finishes while the serial queue is blocked.
            gate.close();
            resume.countDown();
            queued.get(5, TimeUnit.SECONDS);
            assertEquals(0, published.get());
        } finally { gate.close(); resume.countDown(); dispatch.shutdownNow(); }
    }

    @Test public void cleanupDrainsActiveSendWithoutBlockingAnotherRequest() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch sending = new CountDownLatch(1), finish = new CountDownLatch(1), closing = new CountDownLatch(1);
        PeerSendGate gate = new PeerSendGate();
        AtomicInteger published = new AtomicInteger();
        try {
            Future<?> send = workers.submit(() -> gate.send(() -> {
                sending.countDown();
                try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                published.incrementAndGet();
            }));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            Future<?> cleanup = workers.submit(() -> { closing.countDown(); gate.close(); });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            Future<?> independent = workers.submit(() -> {
                try (PeerSendGate other = new PeerSendGate()) { other.send(published::incrementAndGet); }
            });
            independent.get(5, TimeUnit.SECONDS);
            assertFalse("cleanup returned while transport publication was active", cleanup.isDone());
            finish.countDown();
            send.get(5, TimeUnit.SECONDS); cleanup.get(5, TimeUnit.SECONDS);
            assertEquals(2, published.get());
            gate.send(published::incrementAndGet);
            assertEquals(2, published.get());
        } finally { finish.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedTransportSendCannotBeRunAgain() {
        PeerSendGate gate = new PeerSendGate();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> gate.send(() -> {
            calls.incrementAndGet(); throw new IllegalStateException("transport_failed");
        }));
        gate.send(calls::incrementAndGet);
        gate.close();
        assertEquals(1, calls.get());
    }
}
