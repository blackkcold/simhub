package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Event de-noising: no telemetry timer, new process or extra persistent connection. */
public final class TelemetryPolicy {
    private static final String PREF="simhub_telemetry_observed_v1";
    private static final AtomicLong LAST_SAMPLE=new AtomicLong(0);
    private TelemetryPolicy(){}
    public static void onObserved(Context context){
        Context c=context.getApplicationContext();
        long now=SystemClock.elapsedRealtime(),prev=LAST_SAMPLE.get();
        String mode=EnergyPolicy.effectiveMode(c);
        long interval="eco".equals(mode)?60_000L:"realtime".equals(mode)?5_000L:20_000L;
        if(prev>0&&now-prev<interval)return;
        if(!LAST_SAMPLE.compareAndSet(prev,now))return;
        RelayForegroundService.enqueueTelemetry(c);
    }
    static synchronized void sampleOnWorker(Context context){
        Context c=context.getApplicationContext();
        if(!new AgentConfig(c).isEnrolled())return;
        JSONObject state=DeviceTelemetry.capture(c);
        SharedPreferences prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        int battery=state.optInt("batteryPct",-1), old=prefs.getInt("battery",-1);
        boolean charging=state.optBoolean("charging"), wifi=state.optBoolean("wifiConnected");
        boolean cell=state.optBoolean("cellularConnected");
        String signals=state.optJSONArray("signals")==null?"":state.optJSONArray("signals").toString();
        int wifiLevel=state.optInt("wifiSignalLevel",-1);
        boolean networkChanged=!prefs.contains("wifi")||prefs.getBoolean("wifi",false)!=wifi
                ||prefs.getBoolean("cell",false)!=cell
                ||prefs.getInt("wifiLevel",-1)!=wifiLevel
                ||!prefs.getString("signals","").equals(signals);
        boolean batteryChanged=battery>=0&&(old<0||Math.abs(battery-old)>=2
                ||(old>20&&battery<=20)||(old>10&&battery<=10));
        boolean chargingChanged=!prefs.contains("charging")||prefs.getBoolean("charging",false)!=charging;
        if(!networkChanged&&!batteryChanged&&!chargingChanged)return;
        prefs.edit().putInt("battery",battery).putBoolean("charging",charging)
            .putBoolean("wifi",wifi).putBoolean("cell",cell)
            .putInt("wifiLevel",wifiLevel).putString("signals",signals).apply();
        DeviceMetadataSync.requestLight(c);
    }
}
