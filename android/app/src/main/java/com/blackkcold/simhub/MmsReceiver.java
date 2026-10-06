package com.blackkcold.simhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.util.UUID;

public final class MmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){PendingResult p=goAsync();AgentExecutors.io().execute(()->{try{JSONObject payload=new JSONObject().put("direction","in").put("transport","mms").put("status","push_received_metadata_only").put("note","Full carrier MMS PDU download is not implemented in this release.");EventQueue.queue(c,"mms-push-"+UUID.randomUUID(),"mms.received_pending",System.currentTimeMillis()/1000,-1,false,payload,new JSONObject().put("metadataOnly",true));SmsHistorySync.syncMmsMetadata(c,50);}catch(Exception ignored){}finally{p.finish();}});}
}
