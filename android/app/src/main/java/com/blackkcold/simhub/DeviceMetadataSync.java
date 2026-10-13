package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;

/** Coalesced metadata synchronization independent from encrypted SMS transfers.
 * Persisted revisions keep edits made during network calls from being erased. */
public final class DeviceMetadataSync {
    private static final String PREF="simhub_device_metadata_v1";
    private static final long LIGHT_MIN_MS=120_000L;
    private DeviceMetadataSync(){}
    private static SharedPreferences prefs(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    public static synchronized void requestLight(Context c){
        SharedPreferences p=prefs(c);
        p.edit().putLong("revision",p.getLong("revision",0)+1).commit();
        long delay=Math.max(1000L,LIGHT_MIN_MS-(System.currentTimeMillis()-p.getLong("sentAt",0)));
        SyncJobService.scheduleAfter(c,delay);
    }
    public static synchronized void requestFull(Context c){
        prefs(c).edit().putBoolean("fullDirty",true).commit();
        requestLight(c);
        SyncJobService.scheduleNow(c);
    }
    public static boolean fullDirty(Context c){return prefs(c).getBoolean("fullDirty",false);}
    public static boolean needsLight(Context c){
        SharedPreferences p=prefs(c);
        return p.getLong("revision",0)>p.getLong("ackRevision",0)
            && System.currentTimeMillis()-p.getLong("sentAt",0)>=LIGHT_MIN_MS;
    }
    public static long revision(Context c){return prefs(c).getLong("revision",0);}
    public static synchronized void reported(Context c,long revision,boolean full){
        SharedPreferences p=prefs(c);
        SharedPreferences.Editor edit=p.edit()
            .putLong("ackRevision",Math.max(p.getLong("ackRevision",0),revision))
            .putLong("sentAt",System.currentTimeMillis());
        if(full)edit.putBoolean("fullDirty",false);
        edit.commit();
    }
}
