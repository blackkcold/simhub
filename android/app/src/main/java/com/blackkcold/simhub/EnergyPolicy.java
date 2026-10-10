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
    public static void resetSchedule(Context c) {
        prefs(c).edit().remove("maintenance").remove("reconciliation").apply();
    }
}
