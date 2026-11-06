package com.blackkcold.simhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android's own-package replacement signal, not a background Activity launch. */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(c==null||intent==null)return;
        final String action=intent.getAction();
        if(action==null||!action.equals(Intent.ACTION_MY_PACKAGE_REPLACED))return;
        RemoteOta.reconcileInstalledVersion(c);
        DeviceMetadataSync.requestFull(c);
        SyncJobService.schedule(c);
        SyncJobService.scheduleNow(c);
        AgentConfig cfg=new AgentConfig(c);
        if(cfg.isEnrolled()&&cfg.alwaysOn()){
            try{RelayForegroundService.resume(c);}
            catch(Exception e){AppLogger.e(c,"RemoteOTA","Foreground relay recovery deferred to scheduler",e);}
        }
    }
}
