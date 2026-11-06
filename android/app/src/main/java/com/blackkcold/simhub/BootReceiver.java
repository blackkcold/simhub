package com.blackkcold.simhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(c==null||intent==null)return;
        final String action=intent.getAction();
        // An explicit broadcast can bypass the manifest's intent filter.
        // Accept only the system action declared for this receiver.
        if(action==null||!action.equals(Intent.ACTION_BOOT_COMPLETED))return;
        SyncJobService.schedule(c);SyncJobService.scheduleNow(c);
        AgentConfig cfg=new AgentConfig(c);
        if(cfg.isEnrolled()&&cfg.alwaysOn()){
            try{RelayForegroundService.resume(c);}catch(Exception ignored){}
        }
    }
}
