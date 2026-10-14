package com.blackkcold.simhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(i==null||!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()))return;
        SyncJobService.schedule(c);SyncJobService.scheduleNow(c);
        AgentConfig cfg=new AgentConfig(c);
        if(cfg.isEnrolled()&&cfg.alwaysOn()){
            try{RelayForegroundService.resume(c);}catch(Exception ignored){}
        }
    }
}
