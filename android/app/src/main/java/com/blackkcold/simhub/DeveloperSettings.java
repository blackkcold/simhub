package com.blackkcold.simhub;
import android.content.Context;
import android.content.SharedPreferences;
public final class DeveloperSettings {
    private static final String PREF="simhub_developer",KEY_ENABLED="enabled";
    public static boolean isEnabled(Context c){return prefs(c).getBoolean(KEY_ENABLED,false);}
    public static void setEnabled(Context c,boolean enabled){prefs(c).edit().putBoolean(KEY_ENABLED,enabled).apply();}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private DeveloperSettings(){}
}