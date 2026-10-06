package com.blackkcold.simhub;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentExecutors {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "simhub-io");
        t.setDaemon(false);
        return t;
    });
    private static final ExecutorService SYNC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simhub-sync");
        t.setDaemon(false);
        return t;
    });

    public static ExecutorService io(){ return IO; }
    public static ExecutorService sync(){ return SYNC; }
    private AgentExecutors(){}
}
