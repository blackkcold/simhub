package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

/** User-controlled energy policy. No alarm, hidden API, wake lock or background bypass. */
public final class EnergyPolicy {
    public static final String ECO = "eco";
    public static final String BALANCED = "balanced";
    public static final String REALTIME = "realtime";
    private static final String PREFS = "simhub_energy_v1";
    private EnergyPolicy() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static String mode(Context c) {
        String value = prefs(c).getString("mode", BALANCED);
        return ECO.equals(value) || REALTIME.equals(value) ? value : BALANCED;
    }
    public static void setMode(Context c, String value) {
        if (!ECO.equals(value) && !BALANCED.equals(value) && !REALTIME.equals(value))
            throw new IllegalArgumentException("Invalid energy policy");
        prefs(c).edit().putString("mode", value).apply();
    }
    public static String effectiveMode(Context c) {
        if (REALTIME.equals(mode(c))) return REALTIME; // Explicit low-latency choice.
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode() ? ECO : mode(c);
    }
    public static long commandIntervalMs(Context c) {
        return commandIntervalMs(effectiveMode(c));
    }
    public static long commandIntervalMs(String mode) {
        if (REALTIME.equals(mode)) return 15000L; // Server-side long-poll blocks at most 15 s.
        if (ECO.equals(mode)) return 14L * 60 * 1000;
        return 2L * 60 * 1000;
    }
    public static long maintenanceIntervalMs() { return 15L * 60 * 1000; }
    public static long reconciliationIntervalMs() { return 30L * 60 * 1000; }
    public static boolean isRealtime(Context c) { return REALTIME.equals(effectiveMode(c)); }

    public static boolean due(Context c, String key, long intervalMs, long now) {
        long previous = prefs(c).getLong(key, 0);
        return previous <= 0 || now < previous || now - previous >= intervalMs;
    }
    public static void mark(Context c, String key, long now) {
        prefs(c).edit().putLong(key, now).apply();
    }
    public static void stageProviderFollowup(Context c, long delayMs) {
        prefs(c).edit().putLong("provider_followup",System.currentTimeMillis()+delayMs).apply();
    }
    public static long providerFollowupAt(Context c) {
        return prefs(c).getLong("provider_followup",0);
    }
    public static boolean providerFollowupDue(Context c,long now) {
        long at=prefs(c).getLong("provider_followup",0);
        return at>0&&now>=at;
    }
    public static void finishProviderFollowup(Context c) {
        prefs(c).edit().remove("provider_followup").apply();
    }
    public static synchronized void increment(Context c,String key) {
        if(!"commandPolls".equals(key)&&!"commandPollErrors".equals(key)&&
           !"maintenanceRuns".equals(key)&&!"reconciliationRuns".equals(key))return;
        SharedPreferences p=prefs(c);
        p.edit().putLong(key,p.getLong(key,0)+1).apply();
    }
    public static org.json.JSONObject metrics(Context c) {
        SharedPreferences p=prefs(c);
        org.json.JSONObject stats=new org.json.JSONObject();
        try{
            stats.put("mode",mode(c))
                 .put("effectiveMode",effectiveMode(c))
                 .put("commandPolls",p.getLong("commandPolls",0))
                 .put("commandPollErrors",p.getLong("commandPollErrors",0))
                 .put("maintenanceRuns",p.getLong("maintenanceRuns",0))
                 .put("reconciliationRuns",p.getLong("reconciliationRuns",0))
                 .put("nextProviderFollowup",p.getLong("provider_followup",0));
        }catch(org.json.JSONException ignored){}
        return stats;
    }
    public static void resetSchedule(Context c) {
        prefs(c).edit().remove("maintenance").remove("reconciliation").apply();
    }
}
