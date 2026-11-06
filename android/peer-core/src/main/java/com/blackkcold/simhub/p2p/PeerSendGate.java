package com.blackkcold.simhub.p2p;

/** Cancels a queued send, or drains a send that has already entered the transport. */
public final class PeerSendGate implements AutoCloseable {
    private boolean pending = true;

    /** The callback must enqueue directly into the transport, without another executor hop. */
    public synchronized void send(Runnable publication) {
        if (!pending) return;
        pending = false;
        publication.run();
    }

    /** Returning from close guarantees that no callback can publish afterwards. */
    @Override public synchronized void close() {
        pending = false;
    }
}
