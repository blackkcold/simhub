package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CommandProcessor {
    private final Context c;private final ApiClient api;private final LocalStore store;
    CommandProcessor(Context c,ApiClient api){this.c=c.getApplicationContext();this.api=api;store=LocalStore.get(c);}
    public void process(JSONArray arr){if(arr==null)return;for(int i=0;i<arr.length();i++){try{one(arr.getJSONObject(i));}catch(Exception ignored){}}}
    private void one(JSONObject env)throws Exception{
        String id=env.getString("commandId"),type=env.getString("type");long exp=env.optLong("expiresAt",0);if(exp>0&&exp<System.currentTimeMillis()/1000){api.ack(id,"expired",new JSONObject());return;}if(store.isCommandProcessed(id)){api.ack(id,"succeeded",new JSONObject().put("duplicate",true));return;}
        JSONObject p;try{p=new CryptoBox(c).decrypt(env.getJSONObject("ciphertext"),CryptoBox.AAD_COMMAND);}catch(Exception e){store.markCommandProcessed(id);api.ack(id,"rejected",new JSONObject().put("reason","decrypt_failed"));return;}
        if(!type.equals(p.optString("action"))||!id.equals(p.optString("commandId"))){store.markCommandProcessed(id);api.ack(id,"rejected",new JSONObject().put("reason","binding_mismatch"));return;}
        try{
            JSONObject result=new JSONObject();
            switch(type){
                case "sms.send" -> {SmsSender.send(c,id,p.getInt("subscriptionId"),p.getString("to"),p.getString("body"));result.put("submitted",true);}
                case "sms.sync_history" -> result.put("queued",SmsHistorySync.sync(c,Math.min(10000,p.optInt("maxMessages",5000))));
                case "device.refresh_state", "subscription.refresh" -> {api.putState();result.put("refreshed",true);}
                case "diagnostics.request" -> {JSONObject d=StateCollector.collect(c).put("diagnosticAt",System.currentTimeMillis()/1000);EventQueue.diagnostics(c,d);result.put("queued",true);}
                case "ota.check" -> {JSONObject ota=api.ota();EventQueue.diagnostics(c,new JSONObject().put("ota",ota));result.put("checked",true);}
                default -> throw new SecurityException("Unsupported command");
            }
            store.markCommandProcessed(id);api.ack(id,"succeeded",result);
        }catch(Exception e){store.markCommandProcessed(id);api.ack(id,"failed",new JSONObject().put("reason",e.getClass().getSimpleName()));}
    }
}
