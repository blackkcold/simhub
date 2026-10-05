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
    @Override public void onReceive(Context c,Intent i){String id=i.getStringExtra("command_id");if(id==null)return;try{LocalStore store=LocalStore.get(c);if(ACTION_SENT.equals(i.getAction())){boolean ok=getResultCode()==Activity.RESULT_OK;LocalStore.PendingStatus st=store.recordSentPart(id,ok);if(st.exists&&(st.complete||st.failed)){Uri u=Uri.parse(st.providerUri);ContentValues v=new ContentValues();v.put(Telephony.Sms.TYPE,st.failed?Telephony.Sms.MESSAGE_TYPE_FAILED:Telephony.Sms.MESSAGE_TYPE_SENT);c.getContentResolver().update(u,v,null,null);long pid=ContentUris.parseId(u);store.queueEvent("sms-provider-"+pid,st.failed?"sms.failed":"sms.sent",System.currentTimeMillis()/1000,String.valueOf(st.subId),false,new JSONObject().put("stage",st.failed?"modem_failed":"modem_sent"),st.eventCipher);SyncJobService.scheduleNow(c);if(st.failed)store.removePendingSms(id);}}else if(ACTION_DELIVERED.equals(i.getAction())){LocalStore.PendingStatus st=store.recordDeliveredPart(id);if(st.exists&&st.delivered){store.queueEvent("sms-delivery-"+id,"sms.delivered",System.currentTimeMillis()/1000,String.valueOf(st.subId),false,new JSONObject().put("delivery",true),st.eventCipher);store.removePendingSms(id);SyncJobService.scheduleNow(c);}}}catch(Exception ignored){}}
}
