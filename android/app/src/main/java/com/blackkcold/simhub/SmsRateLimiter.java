package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable, fail-closed per-channel remote SMS budget (local device clock). */
public final class SmsRateLimiter {
    private static final long WINDOW_MS = 60L * 60L * 1000L;
    private static final int DEFAULT_LIMIT = 10;
    private static final String PREF = "simhub_sms_send_budget";

    public static synchronized boolean claim(Context context, String channelId) {
        if (channelId == null || channelId.isBlank()) return false;
        SharedPreferences pref = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String key = channelId + ".";
        long now = System.currentTimeMillis();
        long start = pref.getLong(key + "start", 0L);
        int used = pref.getInt(key + "used", 0);
        // Clock rollback does not reset the quota. Refill after a full elapsed hour.
        if (start == 0 || (now >= start && now - start >= WINDOW_MS)) {
            start = now;
            used = 0;
        }
        if (used >= DEFAULT_LIMIT) return false;
        // Reserve quota before the irreversible send. A crashed send still counts.
        return pref.edit().putLong(key + "start", start).putInt(key + "used", used + 1).commit();
    }

    private SmsRateLimiter() {}
}
