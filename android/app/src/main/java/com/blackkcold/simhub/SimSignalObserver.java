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
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import java.util.ArrayList;
import java.util.List;

/** Signal callbacks are active only during the already running Relay service. */
public final class SimSignalObserver {
    private final Context context;
    private final List<Registration> registrations=new ArrayList<>();
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
        clearRegistrations();
        if(subscriptions==null||context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            List<SubscriptionInfo> infos=subscriptions.getActiveSubscriptionInfoList();
            TelephonyManager base=context.getSystemService(TelephonyManager.class);
            if(infos==null||base==null)return;
            for(SubscriptionInfo info:infos){
                if(registrations.size()>=8)break;
                TelephonyManager tm=base.createForSubscriptionId(info.getSubscriptionId());
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                    registrations.add(Api31.register(context,tm));
                }else{
                    PhoneStateListener listener=new PhoneStateListener(){
                        @Override public void onSignalStrengthsChanged(SignalStrength signal){
                            TelemetryPolicy.onObserved(context);
                        }
                    };
                    tm.listen(listener,PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                    registrations.add(()->tm.listen(listener,PhoneStateListener.LISTEN_NONE));
                }
            }
        }catch(Exception ignored){}
    }
    public synchronized void stop(){
        if(!started)return;
        started=false;
        if(subscriptions!=null)try{subscriptions.removeOnSubscriptionsChangedListener(changes);}catch(Exception ignored){}
        clearRegistrations();
    }
    private void clearRegistrations(){
        for(Registration registration:registrations){
            try{registration.unregister();}catch(Exception ignored){}
        }
        registrations.clear();
    }
    private interface Registration { void unregister(); }

    /** Only loaded from the SDK 31 branch; older releases keep PhoneStateListener. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
        static Registration register(Context context,TelephonyManager manager){
            LevelCallback callback=new LevelCallback(context);
            manager.registerTelephonyCallback(context.getMainExecutor(),callback);
            return ()->manager.unregisterTelephonyCallback(callback);
        }
        @RequiresApi(Build.VERSION_CODES.S)
        private static final class LevelCallback extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
            private final Context context;
            LevelCallback(Context context){this.context=context;}
            @Override public void onSignalStrengthsChanged(SignalStrength signal){
                TelemetryPolicy.onObserved(context);
            }
        }
    }
}
