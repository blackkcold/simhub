package com.blackkcold.simhub;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import org.json.JSONObject;
import java.util.UUID;

public final class IncomingSmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        PendingResult pending=goAsync();
        AgentExecutors.io().execute(()->{try{handle(context,intent);}finally{pending.finish();}});
    }
    private void handle(Context c,Intent intent){
        if(Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())){
            // The system default SMS app owns provider writes. We only wake provider
            // reconciliation; never insert an SMS or duplicate SMS_DELIVER here.
            try{
                android.app.role.RoleManager rm=c.getSystemService(android.app.role.RoleManager.class);
                if(rm!=null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS))return;
                if(c.checkSelfPermission(android.Manifest.permission.READ_SMS)
                        !=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
                new AgentConfig(c).recordSmsBroadcast();
                AppLogger.i(c,"SmsReceiver","SMS_RECEIVED broadcast observed in non-default mode");
                SyncJobService.scheduleNow(c);
                // The OEM's default handler may persist the message after this broadcast.
                SyncJobService.scheduleAfter(c,8000L);
            }catch(Exception error){AppLogger.e(c,"SmsReceiver","Non-default SMS wake failed",error);}
            return;
        }
        if(!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction()))return;
        Uri uri=null;
        try{
            SmsMessage[] msgs=Telephony.Sms.Intents.getMessagesFromIntent(intent);if(msgs==null||msgs.length==0)return;
            StringBuilder body=new StringBuilder();for(SmsMessage m:msgs)body.append(m.getMessageBody()==null?"":m.getMessageBody());
            String sender=msgs[0].getOriginatingAddress();long ts=msgs[0].getTimestampMillis();if(ts<=0)ts=System.currentTimeMillis();
            int subId=intExtra(intent,SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,-1);
            ContentValues v=new ContentValues();v.put(Telephony.Sms.ADDRESS,sender);v.put(Telephony.Sms.BODY,body.toString());v.put(Telephony.Sms.DATE,ts);v.put(Telephony.Sms.DATE_SENT,ts);v.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_INBOX);v.put(Telephony.Sms.READ,0);v.put(Telephony.Sms.SEEN,0);if(subId>=0)v.put(Telephony.Sms.SUBSCRIPTION_ID,subId);
            try{uri=c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI,v);}catch(Exception ignored){}
            String eventId=uri!=null?"sms-provider-"+ContentUris.parseId(uri):"sms-live-"+UUID.randomUUID();
            OtpParser.Result otp=OtpParser.parse(body.toString());String contact=ContactResolver.lookup(c,sender);
            JSONObject payload=new JSONObject().put("direction","in").put("sender",sender==null?"":sender).put("body",body.toString()).put("receivedAt",ts/1000).put("subscriptionId",subId);
            ChannelIdentity.Channel channel=ChannelIdentity.forSubscription(c,subId);
            if(channel!=null)payload.put("channelId",channel.channelId).put("channelRevision",channel.revision);
            if(contact!=null)payload.put("contactName",contact);if(otp.detected)payload.put("otp",new JSONObject().put("value",otp.value).put("confidence",otp.confidence));
            JSONObject meta=new JSONObject().put("parts",msgs.length).put("source","sms_deliver");
            boolean queued=EventQueue.queue(c,eventId,"sms.received",ts/1000,subId,otp.detected,payload,meta);
            AppLogger.i(c,"SmsReceiver","SMS received parts="+msgs.length+" subscription="+subId+" otpDetected="+otp.detected+" queued="+queued);
            if(!queued)SyncJobService.scheduleNow(c);
        }catch(Exception error){
            AppLogger.e(c,"SmsReceiver","SMS receive processing failed",error);
            new AgentConfig(c).recordQueueFailure();SyncJobService.scheduleNow(c);
        }
    }
    private static int intExtra(Intent i,String key,int def){try{return i.getIntExtra(key,def);}catch(Exception e){return def;}}
}