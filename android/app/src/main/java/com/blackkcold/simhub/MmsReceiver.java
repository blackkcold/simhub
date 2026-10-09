package com.blackkcold.simhub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/**
 * Preserves incoming WAP PUSH PDUs encrypted locally instead of silently
 * throwing them away. Full carrier-specific MMS media download is NOT
 * implemented; users are notified explicitly of this limitation.
 */
public final class MmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();
        AgentExecutors.io().execute(()->{
            try{handle(context.getApplicationContext(),intent);}
            catch(Exception e){new AgentConfig(context).recordQueueFailure();}
            finally{pending.finish();}
        });
    }

    private static void handle(Context c,Intent intent)throws Exception{
        String id="mms-push-"+UUID.randomUUID();
        byte[] raw=intent.getByteArrayExtra("data");
        String pdu=raw==null?"":Base64.encodeToString(raw,Base64.NO_WRAP);
        JSONObject payload=new JSONObject().put("direction","in").put("transport","mms")
                .put("body","MMS notification received; full media download is not supported by SIM Hub.")
                .put("status","mms_media_unavailable")
                .put("wapPushBase64",pdu).put("contentType",intent.getType()==null?"":intent.getType());
        // Store a local encrypted copy independent of network or enrollment.
        JSONObject encrypted=new CryptoBox(c).encryptLocal(payload);
        File dir=new File(c.getFilesDir(),"mms-push");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Unable to preserve MMS push");
        File tmp=new File(dir,id+".tmp"),dest=new File(dir,id+".json");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(encrypted.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if(!tmp.renameTo(dest))throw new IllegalStateException("MMS push atomic save failed");
        File[] saved=dir.listFiles((d,name)->name.endsWith(".json"));
        if(saved!=null&&saved.length>100){
            Arrays.sort(saved,Comparator.comparingLong(File::lastModified));
            for(int i=0;i<saved.length-100;i++)saved[i].delete();
        }
        long at=System.currentTimeMillis()/1000;
        EventQueue.queue(c,id,"mms.received_pending",at,-1,false,payload,
                new JSONObject().put("metadataOnly",true).put("mmsPushPreserved",true));
        notifyUser(c,id);
        SmsHistorySync.syncMmsMetadata(c,50);
    }

    private static void notifyUser(Context c,String id){
        try{
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            String channel="simhub_mms_alert";
            manager.createNotificationChannel(new NotificationChannel(channel,"Unsupported MMS received",NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open=PendingIntent.getActivity(c,2041,new Intent(c,HubActivity.class),
                    PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(c,channel).setSmallIcon(R.drawable.ic_simhub)
                    .setContentTitle("MMS received — action needed")
                    .setContentText("The encrypted push was saved locally; MMS media download is not supported.")
                    .setContentIntent(open).setAutoCancel(true).build();
            manager.notify(id.hashCode(),n);
        }catch(Exception ignored){}
    }
}
