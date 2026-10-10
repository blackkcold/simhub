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

public final class RelayForegroundService extends Service {
    private final ScheduledExecutorService exec=Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService wakeExecutor=Executors.newSingleThreadExecutor();
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
            SyncJobService.scheduleNow(RelayForegroundService.this);
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
    public static void kick(Context c){SyncJobService.scheduleNow(c);}
    @Override public void onCreate(){super.onCreate();NotificationHelper.ensureChannels(this);if(Build.VERSION.SDK_INT>=34)startForeground(4101,NotificationHelper.relay(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(4101,NotificationHelper.relay(this));AppLogger.i(this,"RelayService","Foreground relay service started");registerSmsObserver();exec.scheduleWithFixedDelay(()->new ApiClient(this).syncCycle(),0,20,TimeUnit.SECONDS);
        wakeExecutor.execute(()->{
            int failures=0;
            while(!Thread.currentThread().isInterrupted()&&new AgentConfig(this).alwaysOn()){
                try{
                    boolean pending=new ApiClient(this).waitForCommands();failures=0;
                    if(pending)new ApiClient(this).syncCycle();
                    if(pending)TimeUnit.MILLISECONDS.sleep(700);
                }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                catch(Exception error){
                    AppLogger.e(this,"RelayService","Command wake-up interrupted",error);
                    failures=Math.min(failures+1,5);
                    try{TimeUnit.SECONDS.sleep(Math.min(60,5L<<Math.min(failures-1,4)));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                }
            }
        });}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(!observingProvider)registerSmsObserver();return START_STICKY;}
    @Override public void onDestroy(){if(observingProvider)try{getContentResolver().unregisterContentObserver(providerObserver);}catch(Exception ignored){}AppLogger.i(this,"RelayService","Foreground relay service stopped");exec.shutdownNow();wakeExecutor.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}