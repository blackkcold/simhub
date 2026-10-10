package com.blackkcold.simhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android's own-package replacement signal, not a background Activity launch. */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        RemoteOta.onPackageReplaced(c);
        SyncJobService.schedule(c);
        SyncJobService.scheduleNow(c);
        AgentConfig cfg=new AgentConfig(c);
        if(cfg.isEnrolled()&&cfg.alwaysOn()){
            try{RelayForegroundService.resume(c);}
            catch(Exception e){AppLogger.e(c,"RemoteOTA","Foreground relay recovery deferred to scheduler",e);}
        }
    }
}
