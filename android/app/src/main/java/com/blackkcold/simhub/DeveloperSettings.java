package com.blackkcold.simhub;
import android.content.Context;
import android.content.SharedPreferences;
public final class DeveloperSettings {
    private static final String PREF="simhub_developer",KEY_ENABLED="enabled",KEY_FORCE_SMS_UNTIL="force_sms_until";
    public static boolean isEnabled(Context c){return prefs(c).getBoolean(KEY_ENABLED,false);}
    public static void setEnabled(Context c,boolean enabled){
        SharedPreferences.Editor edit=prefs(c).edit().putBoolean(KEY_ENABLED,enabled);
        if(!enabled)edit.remove(KEY_FORCE_SMS_UNTIL);
        edit.apply();
    }
    /** Explicit, temporary (one-hour) developer-only SMS transmission override. */
    public static void setForceSmsEnabled(Context c,boolean enabled){
        if(enabled && !isEnabled(c))throw new SecurityException("Enable developer mode first");
        prefs(c).edit().putLong(KEY_FORCE_SMS_UNTIL,
                enabled?System.currentTimeMillis()+3600000L:0L).apply();
    }
    public static boolean isForceSmsEnabled(Context c){
        return isEnabled(c) && prefs(c).getLong(KEY_FORCE_SMS_UNTIL,0L)>System.currentTimeMillis();
    }
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private DeveloperSettings(){}
}