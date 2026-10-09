package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;

public final class EventQueue {
    private static final AtomicLong LAST_WAKE=new AtomicLong(0);
    private EventQueue(){}
    private static void wake(Context c){long now=SystemClock.elapsedRealtime();long prev=LAST_WAKE.get();if(now-prev>1000&&LAST_WAKE.compareAndSet(prev,now))SyncJobService.scheduleNow(c);}
    public static boolean queue(Context c,String id,String kind,long occurredAt,int subId,boolean hasOtp,JSONObject payload,JSONObject metadata){
        try{
            AgentConfig cfg=new AgentConfig(c);
            if(!cfg.isEnrolled())return false;
            String sub=String.valueOf(subId);
            JSONObject cipher=new CryptoBox(c).encryptEvent(payload,id,kind,occurredAt,sub,hasOtp);
            boolean ok=LocalStore.get(c).queueEvent(id,kind,occurredAt,sub,hasOtp,metadata==null?new JSONObject():metadata,cipher);
            if(ok){
                if("sms.received".equals(kind)||"sms.history".equals(kind)||"sms.sent".equals(kind)||"sms.delivered".equals(kind)){
                    String channel=payload.optString("channelId","");
                    SharedPoolClient.stage(c,id,occurredAt,channel,payload);
                }
                if("sms.received".equals(kind))cfg.recordSmsReceived(occurredAt);
                if(!"sms.history".equals(kind))wake(c);
            }
            return ok;
        }catch(Exception ignored){new AgentConfig(c).recordQueueFailure();SyncJobService.scheduleNow(c);return false;}
    }
    public static boolean diagnostics(Context c,JSONObject payload){return queue(c,"diag-"+java.util.UUID.randomUUID(),"device.diagnostics",System.currentTimeMillis()/1000,-1,false,payload,new JSONObject());}
}
