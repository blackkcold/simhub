package com.blackkcold.simhub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RelayForegroundService extends Service {
    private final ScheduledExecutorService exec=Executors.newSingleThreadScheduledExecutor();private final AtomicBoolean busy=new AtomicBoolean(false);
    public static void start(Context c){new AgentConfig(c).setAlwaysOn(true);Intent i=new Intent(c,RelayForegroundService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    public static void stop(Context c){new AgentConfig(c).setAlwaysOn(false);c.stopService(new Intent(c,RelayForegroundService.class));}
    public static void kick(Context c){SyncJobService.scheduleNow(c);}
    @Override public void onCreate(){super.onCreate();NotificationHelper.ensureChannels(this);if(Build.VERSION.SDK_INT>=34)startForeground(4101,NotificationHelper.relay(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(4101,NotificationHelper.relay(this));exec.scheduleWithFixedDelay(this::cycle,0,20,TimeUnit.SECONDS);}
    private void cycle(){if(!busy.compareAndSet(false,true))return;try{new ApiClient(this).syncCycle();}finally{busy.set(false);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){exec.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
