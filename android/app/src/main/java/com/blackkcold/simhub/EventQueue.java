package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;

public final class EventQueue {
    private EventQueue(){}
    public static boolean queue(Context c,String id,String kind,long occurredAt,int subId,boolean hasOtp,JSONObject payload,JSONObject metadata){
        try{
            AgentConfig cfg=new AgentConfig(c);
            if(!cfg.isEnrolled())return false;
            String sub=String.valueOf(subId);
            JSONObject cipher=new CryptoBox(c).encryptEvent(payload,id,kind,occurredAt,sub,hasOtp);
            boolean ok=LocalStore.get(c).queueEvent(id,kind,occurredAt,sub,hasOtp,metadata==null?new JSONObject():metadata,cipher);
            if(ok)SyncJobService.scheduleNow(c);
            return ok;
        }catch(Exception ignored){return false;}
    }
    public static boolean diagnostics(Context c,JSONObject payload){return queue(c,"diag-"+java.util.UUID.randomUUID(),"device.diagnostics",System.currentTimeMillis()/1000,-1,false,payload,new JSONObject());}
}
