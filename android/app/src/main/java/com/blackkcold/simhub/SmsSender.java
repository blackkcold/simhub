package com.blackkcold.simhub;

import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SmsSender {
    private static final Pattern DEST=Pattern.compile("^\\+?[0-9*# ()-]{3,40}$");
    public static void send(Context c,String commandId,int subId,String to,String body)throws Exception{
        if(to==null||!DEST.matcher(to).matches())throw new IllegalArgumentException("Invalid destination");if(body==null||body.isBlank()||body.length()>4000)throw new IllegalArgumentException("Invalid SMS body");
        ContentValues v=new ContentValues();v.put(Telephony.Sms.ADDRESS,to);v.put(Telephony.Sms.BODY,body);v.put(Telephony.Sms.DATE,System.currentTimeMillis());v.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_OUTBOX);v.put(Telephony.Sms.READ,1);v.put(Telephony.Sms.SUBSCRIPTION_ID,subId);
        Uri provider=c.getContentResolver().insert(Telephony.Sms.Outbox.CONTENT_URI,v);if(provider==null)throw new IllegalStateException("Unable to write SMS provider");long providerId=ContentUris.parseId(provider);
        JSONObject eventPayload=new JSONObject().put("direction","out").put("recipient",to).put("body",body).put("occurredAt",System.currentTimeMillis()/1000).put("subscriptionId",subId).put("commandId",commandId);
        JSONObject cipher=new CryptoBox(c).encrypt(eventPayload,CryptoBox.AAD_EVENT);
        SmsManager base=c.getSystemService(SmsManager.class);SmsManager sms=base.createForSubscriptionId(subId);ArrayList<String> parts=sms.divideMessage(body);if(parts.isEmpty())parts.add(body);
        LocalStore.get(c).createPendingSms(commandId,provider.toString(),parts.size(),cipher,subId);
        ArrayList<PendingIntent> sent=new ArrayList<>(),delivered=new ArrayList<>();for(int i=0;i<parts.size();i++){sent.add(pi(c,SmsStatusReceiver.ACTION_SENT,commandId,i));delivered.add(pi(c,SmsStatusReceiver.ACTION_DELIVERED,commandId,1000+i));}
        try{if(parts.size()==1)sms.sendTextMessage(to,null,body,sent.get(0),delivered.get(0));else sms.sendMultipartTextMessage(to,null,parts,sent,delivered);}catch(Exception e){ContentValues fail=new ContentValues();fail.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_FAILED);c.getContentResolver().update(provider,fail,null,null);LocalStore.get(c).queueEvent("sms-provider-"+providerId,"sms.failed",System.currentTimeMillis()/1000,String.valueOf(subId),false,new JSONObject().put("stage","submit"),cipher);LocalStore.get(c).removePendingSms(commandId);throw e;}
    }
    private static PendingIntent pi(Context c,String action,String commandId,int request){Intent i=new Intent(c,SmsStatusReceiver.class).setAction(action).putExtra("command_id",commandId);return PendingIntent.getBroadcast(c,(commandId.hashCode()*31)^request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private SmsSender(){}
}
