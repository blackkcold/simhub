package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated, bounded, metadata-only audit trail for user-initiated compatibility
 * actions. Never store SMS addresses, content, OTPs, enrollment secrets or tokens.
 * It remains available when verbose developer logging is disabled.
 */
public final class CompatibilityAudit {
    private static final String PREF="simhub_compatibility_audit";
    private static final String KEY="recent";
    private static final int LIMIT=60;
    private CompatibilityAudit(){}

    public static synchronized void record(Context context,String action,String outcome){
        if(!CompatibilityPolicy.validAuditToken(action)||!CompatibilityPolicy.validAuditToken(outcome))
            throw new IllegalArgumentException("Invalid compatibility audit category");
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONArray old;
        try{old=new JSONArray(prefs.getString(KEY,"[]"));}catch(Exception error){old=new JSONArray();}
        JSONArray next=new JSONArray();
        try{next.put(new JSONObject().put("at",System.currentTimeMillis()).put("action",action).put("outcome",outcome));}
        catch(Exception ignored){}
        for(int i=0;i<old.length()&&i<LIMIT-1;i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null)next.put(item);
        }
        prefs.edit().putString(KEY,next.toString()).apply();
    }

    public static synchronized String export(Context context){
        String raw=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]");
        try{return new JSONArray(raw).toString(2);}catch(Exception invalid){return "[]";}
    }

    public static synchronized void clear(Context context){
        context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
