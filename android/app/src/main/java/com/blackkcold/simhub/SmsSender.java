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
import java.util.regex.Pattern;

public final class SmsSender {
    private static final Pattern DEST=Pattern.compile("^\\+?[0-9 ()-]{3,40}$");
    public static void send(Context c,String commandId,int subId,String to,String body)throws Exception{
        if(to==null||!DEST.matcher(to).matches())throw new IllegalArgumentException("Invalid destination");
        if(body==null||body.isBlank()||body.length()>4000)throw new IllegalArgumentException("Invalid SMS body");
        long ts=System.currentTimeMillis()/1000;
        // Only the default handler can modify SMS Provider. In companion mode,
        // SmsManager itself sends the message and Android persists the sent row.
        android.app.role.RoleManager role=c.getSystemService(android.app.role.RoleManager.class);
        boolean defaultHandler=role!=null && role.isRoleHeld(android.app.role.RoleManager.ROLE_SMS);
        Uri provider=null;
        long providerId=-1;
        if(defaultHandler){
            ContentValues v=new ContentValues();v.put(Telephony.Sms.ADDRESS,to);v.put(Telephony.Sms.BODY,body);v.put(Telephony.Sms.DATE,System.currentTimeMillis());v.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_OUTBOX);v.put(Telephony.Sms.READ,1);v.put(Telephony.Sms.SUBSCRIPTION_ID,subId);
            provider=c.getContentResolver().insert(Telephony.Sms.Outbox.CONTENT_URI,v);
            if(provider==null)throw new IllegalStateException("Unable to write SMS provider");
            providerId=ContentUris.parseId(provider);
        }
        ChannelIdentity.Channel channel=ChannelIdentity.forSubscription(c,subId);
        JSONObject eventPayload=new JSONObject().put("direction","out").put("recipient",to).put("body",body).put("occurredAt",ts).put("subscriptionId",subId).put("commandId",commandId);
        if(providerId>=0)eventPayload.put("providerId",providerId);
        if(channel!=null)eventPayload.put("channelId",channel.channelId).put("channelRevision",channel.revision);
        JSONObject localCipher=new CryptoBox(c).encryptLocal(eventPayload);
        SmsManager base=c.getSystemService(SmsManager.class);SmsManager sms=base.createForSubscriptionId(subId);ArrayList<String> parts=sms.divideMessage(body);if(parts.isEmpty())parts.add(body);
        LocalStore.get(c).createPendingSms(commandId,provider==null?"":provider.toString(),parts.size(),localCipher,subId);
        ArrayList<PendingIntent> sent=new ArrayList<>(),delivered=new ArrayList<>();for(int i=0;i<parts.size();i++){sent.add(pi(c,SmsStatusReceiver.ACTION_SENT,commandId,i,i));delivered.add(pi(c,SmsStatusReceiver.ACTION_DELIVERED,commandId,i,1000+i));}
        try{if(parts.size()==1)sms.sendTextMessage(to,null,body,sent.get(0),delivered.get(0));else sms.sendMultipartTextMessage(to,null,parts,sent,delivered);AppLogger.i(c,"SmsSender","SMS submitted subscription="+subId+" parts="+parts.size());}
        catch(Exception e){if(provider!=null){ContentValues fail=new ContentValues();fail.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_FAILED);try{c.getContentResolver().update(provider,fail,null,null);}catch(SecurityException denied){AppLogger.w(c,"SmsSender","SMS provider update denied after role change");}}EventQueue.queue(c,providerId>=0?"sms-provider-"+providerId+"-failed":"sms-command-"+commandId+"-failed","sms.failed",System.currentTimeMillis()/1000,subId,false,eventPayload,new JSONObject().put("stage","submit"));LocalStore.get(c).removePendingSms(commandId);AppLogger.e(c,"SmsSender","SMS submission failed subscription="+subId+" parts="+parts.size(),e);throw e;}
    }
    private static PendingIntent pi(Context c,String action,String commandId,int partIndex,int request){Intent i=new Intent(c,SmsStatusReceiver.class).setAction(action).putExtra("command_id",commandId).putExtra("part_index",partIndex);return PendingIntent.getBroadcast(c,(commandId.hashCode()*31)^request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private SmsSender(){}
}