package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Event de-noising: no telemetry timer, new process or extra persistent connection. */
public final class TelemetryPolicy {
    private static final String PREF="simhub_telemetry_observed_v1";
    private TelemetryPolicy(){}
    public static synchronized void onObserved(Context context){
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
