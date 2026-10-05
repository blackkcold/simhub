package com.blackkcold.simhub;

import android.app.Application;

public final class SimHubApp extends Application {
    private static SimHubApp instance;
    public void onCreate() { super.onCreate(); instance = this; SyncJobService.schedule(this); NotificationHelper.ensureChannels(this); }
    public static SimHubApp get() { return instance; }
}
