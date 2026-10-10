package com.blackkcold.simhub;

import android.content.Context;
import android.app.role.RoleManager;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CommandProcessor {
    private final Context c;private final ApiClient api;private final LocalStore store;
    CommandProcessor(Context c,ApiClient api){this.c=c.getApplicationContext();this.api=api;store=LocalStore.get(c);}
    public void process(JSONArray arr){if(arr==null)return;for(int i=0;i<arr.length();i++){try{one(arr.getJSONObject(i));}catch(Exception error){AppLogger.e(c,"Command","Remote command processing failed",error);}}}
    private void ack(String id,String state,JSONObject result){store.finishCommand(id,state);store.queueCommandAck(id,state,result==null?new JSONObject():result);AppLogger.i(c,"Command","Command acknowledged state="+state);}
    private void one(JSONObject env)throws Exception{
        String id=env.getString("commandId"),type=env.getString("type"),idem=env.optString("idempotencyKey",id);AppLogger.i(c,"Command","Processing remote command type="+type);
        long created=env.optLong("createdAt",0),exp=env.optLong("expiresAt",0),now=System.currentTimeMillis()/1000;
        if(exp>0&&exp<now){if(store.claimCommand(id,type))ack(id,"expired",new JSONObject());else store.queueCommandAck(id,"expired",new JSONObject().put("duplicate",true));return;}
        if(!store.claimCommand(id,type)){String state=store.commandState(id);if(state!=null&&!"claimed".equals(state))store.queueCommandAck(id,state,new JSONObject().put("duplicate",true));return;}
        JSONObject p;try{p=new CryptoBox(c).decryptCommand(env.getJSONObject("ciphertext"),id,type,created,exp,idem);}catch(Exception e){AppLogger.e(c,"Command","Command decryption rejected type="+type,e);ack(id,"rejected",new JSONObject().put("reason","decrypt_failed"));return;}
        if(!type.equals(p.optString("action"))||!id.equals(p.optString("commandId"))||p.optLong("expiresAt",exp)!=exp){ack(id,"rejected",new JSONObject().put("reason","binding_mismatch"));return;}
        try{
            JSONObject result=new JSONObject();
            switch(type){
                case "sms.send" -> {if(!SmsSendPolicy.canSend(c)){ack(id,"rejected",new JSONObject().put("reason","sms_send_disabled_by_mode"));return;}if(!p.has("channelId")||p.optLong("channelRevision",0)<=0){ack(id,"failed",new JSONObject().put("reason","channel_identity_required"));return;}ChannelIdentity.Channel ch=ChannelIdentity.resolve(c,p.getString("channelId"),p.getLong("channelRevision"));if(ch==null){ack(id,"failed",new JSONObject().put("reason","subscription_changed"));return;}if(c.checkSelfPermission(android.Manifest.permission.SEND_SMS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){ack(id,"failed",new JSONObject().put("reason","send_sms_permission_missing"));return;}int subId=ch.subscriptionId;if(!SmsRateLimiter.claim(c,ch.channelId)){ack(id,"rejected",new JSONObject().put("reason","sms_hourly_limit"));return;}SmsSender.send(c,id,subId,p.getString("to"),p.getString("body"));ack(id,"submitted",new JSONObject().put("submitted",true).put("subscriptionId",subId));}
                case "sms.sync_recent" -> {int before=store.pendingEventCount();int count=SmsHistorySync.syncRecent(c,Math.min(100,p.optInt("maxMessages",100)));if(count<0){ack(id,"failed",new JSONObject().put("reason","history_scan_failed"));return;}result.put("scanned",count).put("queued",Math.max(0,store.pendingEventCount()-before));ack(id,"succeeded",result);}
                 case "sms.sync_older","sms.sync_history" -> {int before=store.pendingEventCount();int count=SmsHistorySync.syncOlder(c,Math.min(100,p.optInt("maxMessages",100)));if(count<0){ack(id,"failed",new JSONObject().put("reason","history_scan_failed"));return;}result.put("scanned",count).put("queued",Math.max(0,store.pendingEventCount()-before));ack(id,"succeeded",result);}
                case "device.network_policy" -> {
                    boolean enabled=p.optBoolean("enabled",false);
                    String channel=enabled?p.optString("channelId",""):"";
                    long revision=enabled?p.optLong("channelRevision",0):0;
                    if(enabled&&ChannelIdentity.resolve(c,channel,revision)==null){ack(id,"rejected",new JSONObject().put("reason","selected_sim_missing_or_replaced"));return;}
                    new AgentConfig(c).setDataFallback(enabled,channel,revision);
                    String status=NetworkFailoverPolicy.status(c);
                    result.put("enabled",enabled).put("status",status);
                    api.putState();ack(id,"succeeded",result);
                }
                case "device.refresh_state","subscription.refresh" -> {api.putState();result.put("refreshed",true);ack(id,"succeeded",result);}
                case "diagnostics.request" -> {JSONObject d=StateCollector.collect(c).put("diagnosticAt",now).put("requestId",id);EventQueue.diagnostics(c,d);result.put("queued",true);ack(id,"succeeded",result);}
                case "ota.install" -> {JSONObject accepted=RemoteOta.request(c,id,p.optString("targetVersion","latest"));ack(id,"submitted",accepted);}
                case "ota.check" -> {JSONObject ota=api.ota();EventQueue.diagnostics(c,new JSONObject().put("ota",ota));result.put("checked",true);ack(id,"succeeded",result);}
                case "node.rotate_key" -> {if(store.pendingSmsCount()>0){ack(id,"failed",new JSONObject().put("reason","pending_sms"));return;}String keyId=p.getString("keyId");byte[] nodeKey=CryptoBox.ub64(p.getString("nodeKey"));if(nodeKey.length!=32||!CryptoBox.keyId(nodeKey).equals(keyId)){ack(id,"rejected",new JSONObject().put("reason","invalid_node_key"));return;}new AgentConfig(c).rotateNodeKey(keyId,nodeKey);ack(id,"succeeded",new JSONObject().put("rotated",true).put("keyId",keyId));}
                default -> throw new SecurityException("Unsupported command");
            }
        }catch(Exception e){AppLogger.e(c,"Command","Command failed type="+type,e);ack(id,"failed",new JSONObject().put("reason",e.getClass().getSimpleName()));}
    }
}