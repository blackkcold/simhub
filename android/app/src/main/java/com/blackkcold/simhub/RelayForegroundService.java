package com.blackkcold.simhub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.database.ContentObserver;
import android.provider.Telephony;
import android.Manifest;
import android.content.pm.PackageManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RelayForegroundService extends Service {
    public static final String ACTION_RELAY_STATE="com.blackkcold.simhub.RELAY_STATE";
    private static volatile RelayForegroundService ACTIVE;
    private final AtomicBoolean urgentQueued=new AtomicBoolean(false);
    private final AtomicBoolean urgentAgain=new AtomicBoolean(false);
    // Only two bounded workers: command transport and periodic maintenance.
    // No continuous short-interval full synchronization or busy loop.
    private final ScheduledExecutorService exec=Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> maintenanceTask;
    private ScheduledFuture<?> commandTask;
    private int commandFailures=0;
    private volatile long policyEpoch=0;
    private final Handler providerHandler=new Handler(Looper.getMainLooper());
    private long lastProviderWake=0L;
    private final ContentObserver providerObserver=new ContentObserver(providerHandler){
        @Override public void onChange(boolean selfChange){
            if(selfChange || !new AgentConfig(RelayForegroundService.this).isEnrolled())return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastProviderWake<3000L)return;
            lastProviderWake=now;
            new AgentConfig(RelayForegroundService.this).recordSmsProviderChange();
            AppLogger.i(RelayForegroundService.this,"SmsProvider","Provider changed; scheduling encrypted sync");
            RelayForegroundService.kick(RelayForegroundService.this);
        }
    };
    private boolean observingProvider=false;
    private void registerSmsObserver(){
        if(checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI,true,providerObserver);
            observingProvider=true;
            AppLogger.i(this,"SmsProvider","Foreground provider observer registered");
        }catch(SecurityException denied){new AgentConfig(this).recordSmsProviderError("SMS_OBSERVER_PERMISSION_DENIED");AppLogger.e(this,"SmsProvider","Observer permission denied",denied);}
        catch(Exception error){AppLogger.e(this,"SmsProvider","Observer registration failed",error);}
    }

    public static void start(Context c){new AgentConfig(c).setAlwaysOn(true);AppLogger.i(c,"RelayService","Always-on relay enabled");resume(c);}
    public static void resume(Context c){Intent i=new Intent(c,RelayForegroundService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    public static void stop(Context c){new AgentConfig(c).setAlwaysOn(false);AppLogger.i(c,"RelayService","Always-on relay disabled");c.stopService(new Intent(c,RelayForegroundService.class));}
    public static boolean isRunning(){
        RelayForegroundService service=ACTIVE;
        return service!=null&&!service.exec.isShutdown();
    }
    /** Reconfigure timers in place: no foreground-service stop/start or lost observation window. */
    public static void policyChanged(Context c){
        RelayForegroundService service=ACTIVE;
        if(service!=null)service.rescheduleCommands();
        else if(new AgentConfig(c).alwaysOn())SyncJobService.scheduleNow(c);
    }
    private synchronized void rescheduleCommands(){
        policyEpoch++;
        if(commandTask!=null)commandTask.cancel(false);
        commandFailures=0;
        scheduleCommand(0);
    }
    public static void kick(Context c){
        RelayForegroundService live=ACTIVE;
        if(live==null||live.exec.isShutdown()){SyncJobService.scheduleNow(c);return;}
        if(!live.urgentQueued.compareAndSet(false,true)){live.urgentAgain.set(true);return;}
        try{
            live.exec.execute(()->{
                try{new ApiClient(live).syncCycle();}
                finally{
                    live.urgentQueued.set(false);
                    if(live.urgentAgain.getAndSet(false))RelayForegroundService.kick(live);
                }
            });
        }catch(java.util.concurrent.RejectedExecutionException shutdown){
            live.urgentQueued.set(false);
            SyncJobService.scheduleNow(c);
        }
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationHelper.ensureChannels(this);
        if(Build.VERSION.SDK_INT>=34)startForeground(4101,NotificationHelper.relay(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(4101,NotificationHelper.relay(this));
        if(!new AgentConfig(this).alwaysOn()){stopSelf();return;}
        ACTIVE=this;
        sendBroadcast(new Intent(ACTION_RELAY_STATE).setPackage(getPackageName()));
        AppLogger.i(this,"RelayService","Foreground relay service started");
        registerSmsObserver();
        // JobScheduler is the durable recovery mechanism. The foreground service
        // only adds a lightweight remote command check and periodic maintenance.
        SyncJobService.scheduleNow(this);
        maintenanceTask=exec.scheduleWithFixedDelay(
            ()->SyncJobService.scheduleNow(this),
            EnergyPolicy.maintenanceIntervalMs(),EnergyPolicy.maintenanceIntervalMs(),TimeUnit.MILLISECONDS);
        scheduleCommand(0);
    }
    private synchronized void scheduleCommand(long delayMs){
        if(exec.isShutdown()||!new AgentConfig(this).alwaysOn())return;
        long plannedEpoch=policyEpoch;
        commandTask=exec.schedule(()->{
            long next=EnergyPolicy.commandIntervalMs(this);
            try{
                if(new AgentConfig(this).isEnrolled()){
                    EnergyPolicy.increment(this,"commandPolls");
                    new ApiClient(this).pollCommands(EnergyPolicy.isRealtime(this));
                    commandFailures=0;
                }
            }catch(Exception error){
                EnergyPolicy.increment(this,"commandPollErrors");
                AppLogger.e(this,"RelayService","Command check failed",error);
                commandFailures=Math.min(8,commandFailures+1);
                long backoff=Math.min(15L*60*1000,30000L*(1L<<Math.min(5,commandFailures-1)));
                if(error instanceof ApiClient.ApiFailure failure && failure.status==429)
                    backoff=Math.max(backoff,Math.max(10,failure.retryAfter)*1000L);
                next=Math.max(next,backoff);
            }finally{
                // Idle balanced and eco modes do not keep an HTTP long-poll open.
                if(plannedEpoch==policyEpoch)scheduleCommand(next);
            }
        },Math.max(0,delayMs),TimeUnit.MILLISECONDS);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!new AgentConfig(this).alwaysOn()){stopSelf();return START_NOT_STICKY;}
        if(!observingProvider)registerSmsObserver();
        return START_STICKY;
    }
    @Override public void onDestroy(){
        if(observingProvider)try{getContentResolver().unregisterContentObserver(providerObserver);}catch(Exception ignored){}
        AppLogger.i(this,"RelayService","Foreground relay service stopped");
        if(maintenanceTask!=null)maintenanceTask.cancel(true);
        if(commandTask!=null)commandTask.cancel(true);
        exec.shutdownNow();
        if(ACTIVE==this)ACTIVE=null;
        sendBroadcast(new Intent(ACTION_RELAY_STATE).setPackage(getPackageName()));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}