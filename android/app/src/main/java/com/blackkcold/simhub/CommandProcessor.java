package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CommandProcessor {
    private final Context c;private final ApiClient api;private final LocalStore store;
    CommandProcessor(Context c,ApiClient api){this.c=c.getApplicationContext();this.api=api;store=LocalStore.get(c);}
    public void process(JSONArray arr){if(arr==null)return;for(int i=0;i<arr.length();i++){try{one(arr.getJSONObject(i));}catch(Exception ignored){}}}

    private void ack(String id,String state,JSONObject result){
        store.finishCommand(id,state);
        store.queueCommandAck(id,state,result==null?new JSONObject():result);
    }

    private void one(JSONObject env)throws Exception{
        String id=env.getString("commandId"),type=env.getString("type"),idem=env.optString("idempotencyKey",id);
        long created=env.optLong("createdAt",0),exp=env.optLong("expiresAt",0),now=System.currentTimeMillis()/1000;
        if(exp>0&&exp<now){
            if(store.claimCommand(id))ack(id,"expired",new JSONObject());
            else store.queueCommandAck(id,"expired",new JSONObject().put("duplicate",true));
            return;
        }
        if(!store.claimCommand(id)){
            String state=store.commandState(id);
            if(state!=null&&!"claimed".equals(state))store.queueCommandAck(id,state,new JSONObject().put("duplicate",true));
            return;
        }
        JSONObject p;
        try{p=new CryptoBox(c).decryptCommand(env.getJSONObject("ciphertext"),id,type,created,exp,idem);}
        catch(Exception e){ack(id,"rejected",new JSONObject().put("reason","decrypt_failed"));return;}
        if(!type.equals(p.optString("action"))||!id.equals(p.optString("commandId"))||p.optLong("expiresAt",exp)!=exp){
            ack(id,"rejected",new JSONObject().put("reason","binding_mismatch"));return;
        }
        try{
            JSONObject result=new JSONObject();
            switch(type){
                case "sms.send" -> {
                    int subId;
                    if(p.has("channelId")){
                        ChannelIdentity.Channel ch=ChannelIdentity.resolve(c,p.getString("channelId"),p.optLong("channelRevision",0));
                        if(ch==null){ack(id,"failed",new JSONObject().put("reason","subscription_changed"));return;}
                        subId=ch.subscriptionId;
                    }else subId=p.getInt("subscriptionId");
                    SmsSender.send(c,id,subId,p.getString("to"),p.getString("body"));
                    ack(id,"submitted",new JSONObject().put("submitted",true).put("subscriptionId",subId));
                }
                case "sms.sync_history" -> {result.put("queued",SmsHistorySync.sync(c,Math.min(10000,p.optInt("maxMessages",5000))));ack(id,"succeeded",result);}
                case "device.refresh_state","subscription.refresh" -> {api.putState();result.put("refreshed",true);ack(id,"succeeded",result);}
                case "diagnostics.request" -> {JSONObject d=StateCollector.collect(c).put("diagnosticAt",now);EventQueue.diagnostics(c,d);result.put("queued",true);ack(id,"succeeded",result);}
                case "ota.check" -> {JSONObject ota=api.ota();EventQueue.diagnostics(c,new JSONObject().put("ota",ota));result.put("checked",true);ack(id,"succeeded",result);}
                default -> throw new SecurityException("Unsupported command");
            }
        }catch(Exception e){ack(id,"failed",new JSONObject().put("reason",e.getClass().getSimpleName()));}
    }
}
