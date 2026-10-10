package com.blackkcold.simhub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;

/** Durable, opt-in device-side remote OTA. This controls only updates of our own signed package. */
public final class RemoteOta {
    private static final String PREF="simhub_remote_ota_v1";
    private static final String COMMAND="command";
    private RemoteOta(){}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public static boolean supported(Context c){
        return RemoteOtaPolicy.supported(Build.VERSION.SDK_INT,DeveloperSettings.isForceRemoteOtaEnabled(c));
    }
    public static boolean wifiOnly(Context c){return prefs(c).getBoolean("wifiOnly",true);}
    public static void setWifiOnly(Context c,boolean value){prefs(c).edit().putBoolean("wifiOnly",value).putBoolean("stateDirty",true).apply();}
    public static void capabilityChanged(Context c){prefs(c).edit().putBoolean("stateDirty",true).apply();}
    public static boolean needsStateUpload(Context c){return prefs(c).getBoolean("stateDirty",false);}
    public static String currentStage(Context c){return prefs(c).getString("stage","idle");}
    public static void markStateUploaded(Context c,String sentStage){
        synchronized(RemoteOta.class){
            if(sentStage.equals(currentStage(c)))
                prefs(c).edit().putBoolean("stateDirty",false).apply();
        }
    }
    private static String prerequisites(Context c){
        if(wifiOnly(c)){
            ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                return "wifi_required";
        }
        Intent bat=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(bat!=null){
            int level=bat.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            int scale=bat.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            int state=bat.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            if(level>=0 && level*100/Math.max(1,scale)<25 &&
                state!=BatteryManager.BATTERY_STATUS_CHARGING &&
                state!=BatteryManager.BATTERY_STATUS_FULL)return "battery_below_25_percent";
        }
        return "";
    }
    public static boolean active(Context c){
        SharedPreferences p=prefs(c);
        String state=p.getString("stage","idle");
        return !p.getString(COMMAND,"").isBlank() &&
            !("succeeded".equals(state)||"failed".equals(state));
    }
    public static boolean allowed(Context c){return supported(c)&&prefs(c).getBoolean("consent",false);}
    public static void setAllowed(Context c,boolean enabled){
        if(enabled&&!supported(c))throw new SecurityException("Remote OTA requires Android 16+ or developer override");
        prefs(c).edit().putBoolean("consent",enabled).putBoolean("stateDirty",true).apply();
        if(!enabled)cancel(c);
    }
    public static JSONObject state(Context c){
        SharedPreferences p=prefs(c);
        JSONObject j=new JSONObject();
        try{
            j.put("remoteOtaSupported",supported(c))
             .put("remoteOtaNativeSupported",Build.VERSION.SDK_INT>=36)
             .put("remoteOtaDeveloperOverride",DeveloperSettings.isForceRemoteOtaEnabled(c))
             .put("remoteOtaAuthorized",allowed(c))
             .put("remoteOtaInstallPermission",AppUpdater.pmCanInstall(c))
             .put("remoteOtaWifiOnly",wifiOnly(c))
             .put("remoteOtaStage",p.getString("stage","idle"))
             .put("remoteOtaTarget",p.getString("target",""))
             .put("remoteOtaError",p.getString("error",""))
             .put("remoteOtaUpdatedAt",p.getLong("updatedAt",0));
        }catch(Exception ignored){}
        return j;
    }
    public static synchronized JSONObject request(Context c,String commandId,String target)throws Exception{
        if(!allowed(c))throw new SecurityException("remote_ota_not_authorized");
        if(!AppUpdater.pmCanInstall(c))throw new SecurityException("install_sources_permission_required");
        String blocked=prerequisites(c);
        if(!blocked.isBlank())throw new SecurityException(blocked);
        if(!"latest".equals(target)&&!target.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new SecurityException("invalid_target_version");
        SharedPreferences p=prefs(c);
        String active=p.getString(COMMAND,""),status=p.getString("stage","idle");
        if(!active.isEmpty()&&!active.equals(commandId)&&
           !("failed".equals(status)||"succeeded".equals(status)))
            throw new IllegalStateException("ota_already_in_progress");
        p.edit().putString(COMMAND,commandId).putString("target",target)
         .putInt("targetCode",0).putBoolean("cancelRequested",false).putString("stage","checking")
         .putString("error","").putBoolean("stateDirty",true).putLong("updatedAt",System.currentTimeMillis()/1000).commit();
        AppUpdater.check(c,true,(metadata,error)->{
            if(error!=null||metadata==null||!metadata.optBoolean("available")){
                fail(c,error!=null?"release_unavailable":"no_new_version");return;
            }
            if(!"latest".equals(target)&&!target.equals(metadata.optString("versionName"))){
                fail(c,"target_not_latest_stable");return;
            }
            prefs(c).edit().putInt("targetCode",metadata.optInt("versionCode"))
              .putString("target",metadata.optString("versionName")).commit();
            AppUpdater.downloadRemote(c,metadata);
        });
        return new JSONObject().put("submitted",true);
    }
    public static boolean cancelRequested(Context c){return prefs(c).getBoolean("cancelRequested",false);}
    public static synchronized boolean cancel(Context c){
        String status=prefs(c).getString("stage","idle");
        if(!active(c)||"installing".equals(status)||"awaiting_confirmation".equals(status))return false;
        prefs(c).edit().putBoolean("cancelRequested",true).putBoolean("stateDirty",true).commit();
        return true;
    }
    public static void stage(Context c,String stage){
        prefs(c).edit().putString("stage",stage).putBoolean("stateDirty",true).putLong("updatedAt",System.currentTimeMillis()/1000).apply();
        SyncJobService.scheduleNow(c);
    }
    private static void finish(Context c,String status,String reason){
        SharedPreferences p=prefs(c);
        String id=p.getString(COMMAND,"");
        p.edit().putString("stage",status).putString("error",reason)
          .putBoolean("stateDirty",true).putLong("updatedAt",System.currentTimeMillis()/1000).commit();
        if(!id.isBlank()){
            try {
                JSONObject result=new JSONObject();
                if(!reason.isEmpty())result.put("reason",reason);
                LocalStore store=LocalStore.get(c);
                store.finishCommand(id,status);
                store.queueCommandAck(id,status,result);
            }catch(Exception e){AppLogger.e(c,"RemoteOTA","Unable to persist OTA acknowledgement",e);}
        }
        SyncJobService.scheduleNow(c);
    }
    public static void fail(Context c,String reason){finish(c,"failed",reason);}
    public static void onPackageReplaced(Context c){
        SharedPreferences p=prefs(c);
        int expected=p.getInt("targetCode",0);
        String status=p.getString("stage","idle");
        if(expected>0&&!"succeeded".equals(status)&&!"failed".equals(status)){
            int installed=BuildConfig.VERSION_CODE;
            if(installed>=expected)finish(c,"succeeded","");
        }
    }
    public static void installApprovalRequired(Context c){stage(c,"awaiting_confirmation");}
}
