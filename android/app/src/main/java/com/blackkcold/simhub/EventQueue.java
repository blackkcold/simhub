package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;

public final class EventQueue {
    private static final AtomicLong LAST_WAKE=new AtomicLong(0);
    private EventQueue(){}
    private static void wake(Context c){long now=SystemClock.elapsedRealtime();long prev=LAST_WAKE.get();if(now-prev>1000&&LAST_WAKE.compareAndSet(prev,now))RelayForegroundService.kick(c);}
    public static boolean queue(Context c,String id,String kind,long occurredAt,int subId,boolean hasOtp,JSONObject payload,JSONObject metadata){
        try{
            AgentConfig cfg=new AgentConfig(c);
            if(!cfg.isEnrolled()){cfg.recordEventQueueError("not_enrolled");return false;}
            if(LocalStore.get(c).hasSeenEvent(id))return true;
            if("sms.history".equals(kind) && LocalStore.get(c).hasSeenEvent(id))return true;
            String sub=String.valueOf(subId);
            if(kind.startsWith("sms.")){
                payload.put("sourceDeviceName",cfg.deviceName());
                String channelId=payload.optString("channelId","");
                if(!channelId.isBlank()){
                    JSONObject profile=SimTagStore.get(c,channelId,payload.optLong("channelRevision",1));
                    if(!profile.optString("tag","").isBlank())payload.put("simTag",profile.optString("tag",""));
                    if(!profile.optString("tail","").isBlank())payload.put("simTail",profile.optString("tail",""));
                }
            }
            JSONObject cipher=new CryptoBox(c).encryptEvent(payload,id,kind,occurredAt,sub,hasOtp);
            boolean ok=LocalStore.get(c).queueEvent(id,kind,occurredAt,sub,hasOtp,metadata==null?new JSONObject():metadata,cipher);
            if(!ok){cfg.recordEventQueueError("database_insert_rejected");cfg.recordQueueFailure();AppLogger.w(c,"EventQueue","Event not staged: database rejected insertion");return false;}
            if(ok){
                if("sms.received".equals(kind)||"sms.history".equals(kind)||"sms.sent".equals(kind)||"sms.delivered".equals(kind)||"sim.profile".equals(kind)){
                    String channel=payload.optString("channelId","");
                    SharedPoolClient.stage(c,id,occurredAt,channel,payload);
                }
                if("sms.received".equals(kind))cfg.recordSmsReceived(occurredAt);
                if(!"sms.history".equals(kind))wake(c);
            }
            return ok;
        }catch(Exception failure){AgentConfig cfg=new AgentConfig(c);cfg.recordQueueFailure();cfg.recordEventQueueError(failure.getClass().getSimpleName());AppLogger.e(c,"EventQueue","Encrypted SMS event could not be staged",failure);SyncJobService.scheduleNow(c);return false;}
    }
    public static boolean diagnostics(Context c,JSONObject payload){return queue(c,"diag-"+java.util.UUID.randomUUID(),"device.diagnostics",System.currentTimeMillis()/1000,-1,false,payload,new JSONObject());}
}
