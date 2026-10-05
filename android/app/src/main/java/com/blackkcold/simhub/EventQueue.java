package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;

public final class EventQueue {
    private EventQueue(){}
    public static boolean queue(Context c,String id,String kind,long occurredAt,int subId,boolean hasOtp,JSONObject payload,JSONObject metadata){
        try{
            if(!new AgentConfig(c).isEnrolled()) return false;
            JSONObject cipher=new CryptoBox(c).encrypt(payload,CryptoBox.AAD_EVENT);
            LocalStore.get(c).queueEvent(id,kind,occurredAt,String.valueOf(subId),hasOtp,metadata==null?new JSONObject():metadata,cipher);
            if(new AgentConfig(c).alwaysOn()) RelayForegroundService.kick(c);
            return true;
        }catch(Exception ignored){return false;}
    }
    public static boolean diagnostics(Context c,JSONObject payload){return queue(c,"diag-"+java.util.UUID.randomUUID(),"device.diagnostics",System.currentTimeMillis()/1000,-1,false,payload,new JSONObject());}
}
