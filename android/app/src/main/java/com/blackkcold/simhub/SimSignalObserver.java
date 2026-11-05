package com.blackkcold.simhub;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.List;

/** Signal callbacks are active only during the already running Relay service. */
public final class SimSignalObserver {
    private final Context context;
    private final List<TelephonyManager> managers=new ArrayList<>();
    private final List<TelephonyCallback> callbacks=new ArrayList<>();
    private final List<PhoneStateListener> legacy=new ArrayList<>();
    private SubscriptionManager subscriptions;
    private boolean started=false;
    private final SubscriptionManager.OnSubscriptionsChangedListener changes=
        new SubscriptionManager.OnSubscriptionsChangedListener(){
            @Override public void onSubscriptionsChanged(){
                if(started){rebind();TelemetryPolicy.onObserved(context);}
            }
        };
    SimSignalObserver(Context context){this.context=context.getApplicationContext();}
    public synchronized void start(){
        if(started)return;
        started=true;
        subscriptions=context.getSystemService(SubscriptionManager.class);
        if(subscriptions==null)return;
        try{
            if(Build.VERSION.SDK_INT>=31)
                subscriptions.addOnSubscriptionsChangedListener(context.getMainExecutor(),changes);
            else subscriptions.addOnSubscriptionsChangedListener(changes);
        }catch(Exception ignored){}
        rebind();
    }
    private synchronized void rebind(){
        for(int i=0;i<managers.size();i++){
            try{
                if(Build.VERSION.SDK_INT>=31)managers.get(i).unregisterTelephonyCallback(callbacks.get(i));
                else managers.get(i).listen(legacy.get(i),PhoneStateListener.LISTEN_NONE);
            }catch(Exception ignored){}
        }
        managers.clear();callbacks.clear();legacy.clear();
        if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            List<SubscriptionInfo> infos=subscriptions.getActiveSubscriptionInfoList();
            TelephonyManager base=context.getSystemService(TelephonyManager.class);
            if(infos==null||base==null)return;
            for(SubscriptionInfo info:infos){
                if(managers.size()>=8)break;
                TelephonyManager tm=base.createForSubscriptionId(info.getSubscriptionId());
                if(Build.VERSION.SDK_INT>=31){
                    LevelCallback callback=new LevelCallback(context);
                    tm.registerTelephonyCallback(context.getMainExecutor(),callback);
                    managers.add(tm);callbacks.add(callback);
                }else{
                    PhoneStateListener listener=new PhoneStateListener(){
                        @Override public void onSignalStrengthsChanged(SignalStrength signal){
                            TelemetryPolicy.onObserved(context);
                        }
                    };
                    tm.listen(listener,PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                    managers.add(tm);legacy.add(listener);
                }
            }
        }catch(Exception ignored){}
    }
    public synchronized void stop(){
        if(!started)return;
        started=false;
        if(subscriptions!=null)try{subscriptions.removeOnSubscriptionsChangedListener(changes);}catch(Exception ignored){}
        for(int i=0;i<managers.size();i++){
            try{
                if(Build.VERSION.SDK_INT>=31)managers.get(i).unregisterTelephonyCallback(callbacks.get(i));
                else managers.get(i).listen(legacy.get(i),PhoneStateListener.LISTEN_NONE);
            }catch(Exception ignored){}
        }
        managers.clear();callbacks.clear();legacy.clear();
    }
    private static final class LevelCallback extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        private final Context context;
        LevelCallback(Context context){this.context=context;}
        @Override public void onSignalStrengthsChanged(SignalStrength signal){
            TelemetryPolicy.onObserved(context);
        }
    }
}
