package com.blackkcold.simhub;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.os.Build;
import android.os.Looper;
import android.os.CancellationSignal;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consent-gated one-shot location. No location foreground service or background tracking.
 * Coordinates are encrypted by the existing Node Key event envelope before network upload.
 */
public final class NodeLocationManager {
    private static final String PREF="simhub_location_privacy_v1";
    private NodeLocationManager(){}
    private static SharedPreferences prefs(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    public static boolean remoteAllowed(Context c){return enabled(c)&&prefs(c).getBoolean("remoteAllowed",false);}
    public static void setRemoteAllowed(Context c,boolean value){
        prefs(c).edit().putBoolean("remoteAllowed",enabled(c)&&value).commit();
        DeviceMetadataSync.requestFull(c);
    }
    private static String epoch(Context c){return prefs(c).getString("epoch","");}
    public static boolean purgePending(Context c){return prefs(c).getBoolean("purgePending",false);}
    public static void setEnabled(Context c,boolean enabled){
        SharedPreferences p=prefs(c);
        SharedPreferences.Editor editor=p.edit()
            .putBoolean("enabled",enabled)
            .putBoolean("purgePending",!enabled||purgePending(c));
        if(enabled&&!p.getBoolean("enabled",false)){
            editor.putString("epoch",UUID.randomUUID().toString())
                .remove("registeredEpoch").putBoolean("remoteAllowed",false);
        }
        if(!enabled){
            editor.remove("epoch").remove("registeredEpoch").putBoolean("remoteAllowed",false);
        }
        editor.commit();
        if(!enabled)LocalStore.get(c).clearPendingLocation();
        DeviceMetadataSync.requestFull(c);
    }
    public static void purgeIfNeeded(Context c,ApiClient api)throws Exception{
        if(!purgePending(c))return;
        api.purgeLocation();
        prefs(c).edit().putBoolean("purgePending",false).commit();
    }
    public static boolean permissionGranted(Context c){
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            ||c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private static Location current(Context c)throws Exception{
        if(!permissionGranted(c))throw new SecurityException("permission_required");
        LocationManager manager=c.getSystemService(LocationManager.class);
        if(manager==null||!manager.isLocationEnabled())throw new IllegalStateException("location_disabled");
        String provider=manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ?LocationManager.NETWORK_PROVIDER:LocationManager.GPS_PROVIDER;
        if(!manager.isProviderEnabled(provider))throw new IllegalStateException("location_disabled");
        Location cached=null;
        try{cached=manager.getLastKnownLocation(provider);}catch(SecurityException denied){throw denied;}
        if(cached!=null&&cached.hasAccuracy()&&cached.getAccuracy()<=2000f
                &&SystemClock.elapsedRealtimeNanos()-cached.getElapsedRealtimeNanos()<=300_000_000_000L)
            return cached;
        CancellationSignal cancel=new CancellationSignal();
        CompletableFuture<Location> result=new CompletableFuture<>();
        try{
            LocationListener legacy=new LocationListener(){
                @Override public void onLocationChanged(Location sample){result.complete(sample);}
            };
            if(Build.VERSION.SDK_INT>=30)
                manager.getCurrentLocation(provider,cancel,c.getMainExecutor(),result::complete);
            else manager.requestSingleUpdate(provider,legacy,Looper.getMainLooper());
            Location sample;
            try{sample=result.get(20,TimeUnit.SECONDS);}
            finally{if(Build.VERSION.SDK_INT<30)manager.removeUpdates(legacy);}
            if(sample==null)throw new IllegalStateException("location_unavailable");
            if(!sample.hasAccuracy())throw new IllegalStateException("location_accuracy_unknown");
            return sample;
        }catch(java.util.concurrent.TimeoutException timedOut){
            throw new IllegalStateException("location_timeout");
        }finally{cancel.cancel();}
    }
    public static JSONObject captureAndQueue(Context c)throws Exception{
        if(!enabled(c))throw new SecurityException("location_sharing_disabled");
        AgentConfig config=new AgentConfig(c);
        if(!config.isEnrolled())throw new IllegalStateException("not_enrolled");
        // A prior opt-out must be applied before another point may be published.
        ApiClient api=new ApiClient(c);
        purgeIfNeeded(c,api);
        String consentEpoch=epoch(c);
        if(consentEpoch.isBlank())throw new SecurityException("location_consent_missing");
        if(!consentEpoch.equals(prefs(c).getString("registeredEpoch",""))){
            api.activateLocation(consentEpoch);
            prefs(c).edit().putString("registeredEpoch",consentEpoch).commit();
        }
        Location location=current(c);
        if(!enabled(c))throw new SecurityException("location_sharing_disabled");
        if(!Double.isFinite(location.getLatitude())||!Double.isFinite(location.getLongitude())
                ||Math.abs(location.getLatitude())>90||Math.abs(location.getLongitude())>180)
            throw new IllegalStateException("invalid_location");
        if(!consentEpoch.equals(epoch(c)))throw new SecurityException("location_consent_changed");
        long ts=System.currentTimeMillis()/1000+1;
        JSONObject inner=new JSONObject()
            .put("latitude",location.getLatitude())
            .put("longitude",location.getLongitude())
            .put("accuracyMeters",location.getAccuracy())
            .put("sampledAt",location.getTime()/1000)
            .put("source","android-one-shot");
        String id="location-"+UUID.randomUUID();
        if(!EventQueue.queue(c,id,"device.location",ts,-1,false,inner,
                new JSONObject().put("locationEpoch",consentEpoch)))
            throw new IllegalStateException("location_queue_failed");
        api.flushEvents();
        return new JSONObject().put("sampledAt",inner.optLong("sampledAt"))
            .put("published",LocalStore.get(c).hasUploadedReceipt(id))
            .put("queued",LocalStore.get(c).isEventPending(id));
    }
}
