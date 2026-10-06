package com.blackkcold.simhub;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import org.json.JSONObject;

public final class SmsStatusReceiver extends BroadcastReceiver {
    public static final String ACTION_SENT="com.blackkcold.simhub.SMS_SENT";
    public static final String ACTION_DELIVERED="com.blackkcold.simhub.SMS_DELIVERED";

    @Override public void onReceive(Context c,Intent i){
        PendingResult pending=goAsync();
        int resultCode=getResultCode();
        AgentExecutors.io().execute(()->{try{handle(c,i,resultCode);}finally{pending.finish();}});
    }

    private void handle(Context c,Intent i,int resultCode){
        String id=i.getStringExtra("command_id");if(id==null)return;
        int partIndex=i.getIntExtra("part_index",-1);
        try{
            LocalStore store=LocalStore.get(c);
            if(ACTION_SENT.equals(i.getAction())){
                boolean ok=resultCode==Activity.RESULT_OK;
                LocalStore.PendingStatus st=store.recordSentPart(id,partIndex,ok,resultCode);
                if(st.exists&&(st.complete||st.failed)){
                    Uri u=Uri.parse(st.providerUri);long pid=ContentUris.parseId(u);
                    ContentValues v=new ContentValues();v.put(Telephony.Sms.TYPE,st.failed?Telephony.Sms.MESSAGE_TYPE_FAILED:Telephony.Sms.MESSAGE_TYPE_SENT);c.getContentResolver().update(u,v,null,null);
                    JSONObject payload=new CryptoBox(c).decryptLocal(st.eventCipher);
                    String state=st.failed?"failed":"sent";
                    EventQueue.queue(c,"sms-provider-"+pid+"-"+state,st.failed?"sms.failed":"sms.sent",System.currentTimeMillis()/1000,st.subId,false,payload,new JSONObject().put("stage",st.failed?"modem_failed":"modem_sent").put("resultCode",resultCode).put("partIndex",partIndex));
                    store.finishCommand(id,state);
                    store.queueCommandAck(id,state,new JSONObject().put("modemAccepted",!st.failed).put("resultCode",resultCode));
                    if(st.failed)store.removePendingSms(id);
                    SyncJobService.scheduleNow(c);
                }
            }else if(ACTION_DELIVERED.equals(i.getAction())){
                boolean ok=resultCode==Activity.RESULT_OK;
                LocalStore.PendingStatus st=store.recordDeliveredPart(id,partIndex,ok,resultCode);
                if(!st.exists)return;
                Uri u=Uri.parse(st.providerUri);long pid=ContentUris.parseId(u);
                JSONObject payload=new CryptoBox(c).decryptLocal(st.eventCipher);
                if(st.deliveryFailed){
                    EventQueue.queue(c,"sms-provider-"+pid+"-delivery-failed","sms.failed",System.currentTimeMillis()/1000,st.subId,false,payload,new JSONObject().put("stage","delivery_failed").put("resultCode",resultCode).put("partIndex",partIndex));
                    store.finishCommand(id,"failed");
                    store.queueCommandAck(id,"failed",new JSONObject().put("reason","delivery_failed").put("resultCode",resultCode));
                    store.removePendingSms(id);
                    SyncJobService.scheduleNow(c);
                }else if(st.delivered){
                    EventQueue.queue(c,"sms-provider-"+pid+"-delivered","sms.delivered",System.currentTimeMillis()/1000,st.subId,false,payload,new JSONObject().put("delivery",true));
                    store.finishCommand(id,"delivered");
                    store.queueCommandAck(id,"delivered",new JSONObject().put("delivered",true));
                    store.removePendingSms(id);
                    SyncJobService.scheduleNow(c);
                }
            }
        }catch(Exception ignored){}
    }
}
