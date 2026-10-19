package com.blackkcold.simhub.p2p;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Telephony;
import com.blackkcold.simhub.*;
import org.json.*;
import java.util.*;

/** Permission checks and command receipts apply identically on every P2P route. */
public final class PeerRpc {
    private final PeerStore store;
    private final Context c;
    public PeerRpc(PeerStore store){this.store=store;this.c=store.context();}
    public JSONObject dispatch(String peerId,String id,JSONObject request)throws Exception{
        synchronized(store){
            JSONObject peer=store.peer(peerId);
            boolean pendingV2=peer.optInt("protocolVersion",1)>=2
                &&"PENDING".equals(peer.optString("pairState"));
            if((!"ACTIVE".equals(peer.optString("pairState"))&&!pendingV2)
                ||(peer.optInt("protocolVersion",1)<2&&!"NODE".equals(peer.optString("localRole"))))
                throw new SecurityException("peer_not_authorized");
            String action=request.getString("action");
            boolean relayAction=action.startsWith("relay.");
            // Relay authorization is orthogonal to SMS/management grants.
            if(relayAction&&(!peer.optBoolean("relayLiteAllowRemote",false)
                ||!RelayLiteMailbox.get(c).enabled()))
                throw new SecurityException("relay_lite_not_authorized");
            if(!relayAction&&!"peer.revoke".equals(action)&&!PeerGrantPolicy.allows(peer,action,true))
                throw new SecurityException("peer_permission_denied");
            // Only an authenticated transport should invoke dispatch; in a v2
            // pending relationship that proves the responder accepted the QR.
            if(pendingV2){peer.put("pairState","ACTIVE");store.update(peer);}
            String granted=peer.optInt("protocolVersion",1)>=2
                ?peer.optString("grantsToRemote",""):peer.optString("permissions","");
            Set<String> grants=new HashSet<>(Arrays.asList(granted.split(",")));
            return switch(action){
                case "device.state" -> {require(grants,"state");yield state(peer);}
                case "sms.page" -> {require(grants,"read");require(grants,"history");yield page(peer,request);}
                case "events.page" -> {require(grants,"read");yield store.events(peerId,request.optLong("after",0));}
                case "events.ack" -> {require(grants,"read");store.ackEvents(peerId,request.getJSONArray("sequences"));yield new JSONObject().put("state","acknowledged");}
                case "sms.send" -> {require(grants,"send");yield send(peerId,id,request);}
                case "command.status" -> {require(grants,"send");String command=request.getString("commandId");String localId="p2p-"+PeerCrypto.digest(PeerCrypto.utf8(peerId+"|"+command));String status=LocalStore.get(c).commandState(localId);JSONObject receipt=store.receipt(peerId,command,null);yield new JSONObject().put("state",status!=null?status:receipt!=null?receipt.optString("state","unknown"):"unknown");}
                case "relay.put" -> {
                    String target=request.getString("target");
                    JSONObject recipient=store.peer(target);
                    if(!"ACTIVE".equals(recipient.optString("pairState"))
                        ||!recipient.optBoolean("relayLiteAllowRemote",false))
                        throw new SecurityException("relay_lite_target_not_authorized");
                    yield RelayLiteMailbox.get(c).enqueue(peerId,target,request.getJSONObject("frame"));
                }
                case "relay.pull" -> {
                    yield RelayLiteMailbox.get(c).pull(peerId,request.optInt("limit",2));
                }
                case "relay.ack" -> {
                    boolean removed=RelayLiteMailbox.get(c).ack(peerId,
                        request.getString("source"),request.getString("messageId"));
                    yield new JSONObject().put("state","acknowledged").put("removed",removed);
                }
                case "peer.revoke" -> {store.revoke(peerId);yield new JSONObject().put("state","revoked");}
                default -> throw new SecurityException("unsupported_peer_action");
            };
        }
    }
    private static void require(Set<String> grants,String permission){if(!grants.contains(permission))throw new SecurityException("peer_permission_denied");}
    /** The paired controller learns the binding id only over the authenticated pair channel. */
    private JSONObject state(JSONObject peer)throws Exception{
        JSONObject full=StateCollector.collect(c),state=new JSONObject();
        for(String field:List.of("androidVersion","sdk","model","appVersion","batteryPct","charging","network","smsRoleHeld","smsMode","smsReadPermission","smsSendPermission","smsSendAllowed","smsDeveloperOverride","subscriptions","channels","smsReceiveOperational"))if(full.has(field))state.put(field,full.get(field));
        return new JSONObject().put("state","succeeded").put("device",state).put("id",store.myId()).put("relayHint",PeerPairingManager.relayHint(c))
            .put("signaling",new JSONObject().put("capability",PeerCenterProtocol.MODE)
                .put("consent",peer.optBoolean("signalingConsent",false))
                .put("bindingId",peer.optString("signalingCenterBinding",""))
                .put("revision",peer.optInt("signalingConsentRevision",0)));
    }
    private JSONObject page(JSONObject peer,JSONObject req)throws Exception{
        if(!SmsAccessPolicy.canRead(c))return new JSONObject().put("state","rejected").put("reason","read_sms_permission_missing");
        int limit=Math.max(1,Math.min(100,req.optInt("limit",100)));JSONObject cursor=req.optJSONObject("cursor");
        String selection=Telephony.Sms.TYPE+"<>?";List<String> args=new ArrayList<>();args.add(String.valueOf(Telephony.Sms.MESSAGE_TYPE_DRAFT));
        if(cursor!=null){long date=cursor.getLong("date"),id=cursor.getLong("id");if(date<0||id<0)throw new SecurityException("invalid_history_cursor");selection+=" AND (date<? OR (date=? AND _id<?))";args.add(String.valueOf(date));args.add(String.valueOf(date));args.add(String.valueOf(id));}
        Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,selection);query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args.toArray(new String[0]));query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"date DESC, _id DESC");query.putInt(ContentResolver.QUERY_ARG_LIMIT,limit+1);
        String[] projection={"_id","address","body","date","type","sub_id"};JSONArray rows=new JSONArray();int bytes=0;long lastDate=0,lastId=0;boolean more=false;
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,projection,query,null)){
            if(cur==null)throw new IllegalStateException("sms_provider_unavailable");
            while(cur.moveToNext()){
                if(rows.length()==limit){more=true;break;}
                long providerId=cur.getLong(0),date=cur.getLong(3);int type=cur.getInt(4),sub=cur.isNull(5)?-1:cur.getInt(5);String address=cur.getString(1),body=cur.getString(2);if(address==null)address="";if(body==null)body="";
                ChannelIdentity.Channel ch=ChannelIdentity.forHistoricalSubscription(c,sub,date);String sim="SIM "+sub;
                if(ch!=null){JSONObject tags=SimTagStore.get(c,ch.channelId,ch.revision);sim=tags.optString("tag",sim);}
                OtpParser.Result otp=type==Telephony.Sms.MESSAGE_TYPE_INBOX?OtpParser.parse(body):OtpParser.parse("");
                JSONObject message=new JSONObject().put("id","peer-"+store.myId()+"-"+providerId).put("thread",PeerCrypto.digest(PeerCrypto.utf8(store.myId()+"|"+sub+"|"+address))).put("sender",address).put("address",address).put("body",body.substring(0,Math.min(4000,body.length()))).put("timestamp",date/1000).put("inbound",type==Telephony.Sms.MESSAGE_TYPE_INBOX).put("device",android.os.Build.MODEL).put("sim",sim).put("sourceId",store.myId()+"-"+sub).put("otp",otp.detected?otp.value:"").put("failed",type==Telephony.Sms.MESSAGE_TYPE_FAILED);
                int size=message.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(bytes+size>300000){more=true;break;}rows.put(message);bytes+=size;lastDate=date;lastId=providerId;
            }
        }
        JSONObject out=new JSONObject().put("state","succeeded").put("type","snapshot").put("v",1).put("messages",rows).put("hasMore",more);
        if(rows.length()>0)out.put("cursor",new JSONObject().put("date",lastDate).put("id",lastId));return out;
    }
    private JSONObject send(String peer,String id,JSONObject req)throws Exception{
        long now=System.currentTimeMillis()/1000,expires=req.getLong("commandExpires");
        String digest=PeerCrypto.digest(PeerCrypto.utf8(req.toString()));JSONObject receipt=store.receipt(peer,id,digest);if(receipt!=null)return receipt;
        if(expires<now||expires>now+300)return new JSONObject().put("state","expired");
        JSONObject existing=store.claim(peer,id,digest);if(existing!=null)return existing;
        JSONObject result;
        String localId="p2p-"+PeerCrypto.digest(PeerCrypto.utf8(peer+"|"+id));
        try{
            SmsSendPolicy.requireCanSend(c);long revision=req.getLong("channelRevision");if(revision<1)throw new SecurityException("channel_identity_required");ChannelIdentity.Channel channel=ChannelIdentity.resolve(c,req.getString("channelId"),revision);if(channel==null)throw new SecurityException("subscription_changed");
            if(!SmsRateLimiter.claim(c,channel.channelId))throw new SecurityException("sms_hourly_limit");
            if(!LocalStore.get(c).claimCommand(localId,"sms.send"))result=new JSONObject().put("state","indeterminate");
            else{SmsSender.send(c,localId,channel.subscriptionId,req.getString("to"),req.getString("body"));LocalStore.get(c).finishCommand(localId,"submitted");result=new JSONObject().put("state","submitted").put("commandId",id);}
        }catch(Exception error){result=new JSONObject().put("state","failed").put("reason",error instanceof SecurityException?"permission_or_sim_policy_rejected":"sms_submission_failed");LocalStore.get(c).finishCommand(localId,"failed");}
        store.finish(peer,id,result);return result;
    }
}
