package com.blackkcold.simhub;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiClient {
    private static final AtomicBoolean SYNC_BUSY=new AtomicBoolean(false);
    private static final long TOKEN_ROTATE_AFTER=60L*86400;
    private static volatile boolean BATCH_SUPPORTED=true;
    private static final int BATCH_SIZE=20;
    public static final class ApiFailure extends IOException {
        public final int status,retryAfter;public final String code;
        ApiFailure(int status,String code,String message,int retryAfter){super(message);this.status=status;this.code=code;this.retryAfter=retryAfter;}
    }
    private final Context c;private final AgentConfig cfg;
    public ApiClient(Context c){this.c=c.getApplicationContext();cfg=new AgentConfig(c);}
    public static JSONObject enroll(String server,String token,String name)throws Exception{return enroll(server,token,name,null);}
    public static JSONObject enroll(String server,String token,String name,String bootstrapProof)throws Exception{
        requireHttps(server);
        JSONObject b=new JSONObject().put("token",token).put("name",name).put("model",Build.MANUFACTURER+" "+Build.MODEL).put("osVersion",Build.VERSION.RELEASE).put("appVersion",BuildConfig.VERSION_NAME).put("nodeType","android").put("capabilities",new JSONArray().put("sms.receive").put("sms.send").put("sms.history").put("signal.basic").put("dual-sim"));
        if(bootstrapProof!=null&&!bootstrapProof.isBlank())b.put("bootstrapProof",bootstrapProof);
        return raw(server+"/api/v1/enroll","POST",b,null,null);
    }
    public void syncCycle(){
        if(cfg.resetPending()){
            if(!SYNC_BUSY.compareAndSet(false,true))return;
            try{performPendingReset();}
            finally{SYNC_BUSY.set(false);}
            return;
        }
        if(!cfg.isEnrolled()||!SYNC_BUSY.compareAndSet(false,true))return;
        long delay=cfg.nextSyncAllowedAt()-System.currentTimeMillis();
        if(delay>0){SYNC_BUSY.set(false);SyncJobService.scheduleAfter(c,delay);return;}
        int pendingBefore=LocalStore.get(c).pendingEventCount();
        try{
            if(checkServerReset())return;
            rotateDeviceTokenIfNeeded();LocalStore store=LocalStore.get(c);store.recoverStaleClaims();
            for(String id:store.expireStalePendingSms(48L*3600)){store.finishCommand(id,"failed");store.queueCommandAck(id,"failed",new JSONObject().put("reason","status_timeout"));}
            flushEvents();int scanned=LocalStore.get(c).pendingEventCount()<200?SmsHistorySync.sync(c,30):0;
            flushEvents();flushCommandAcks();fetchCommands();flushCommandAcks();putState();heartbeat();cfg.recordSyncSuccess();cfg.resetSyncBackoff();
            if(scanned>=30||store.pendingEventCount()>0)SyncJobService.scheduleAfter(c,5000);
            if(pendingBefore>0)AppLogger.i(c,"ApiClient","Sync cycle completed pendingBefore="+pendingBefore+" pendingAfter="+store.pendingEventCount());
        }catch(Exception error){
            String reason=error instanceof ApiFailure f?"HTTP "+f.status+" "+f.code:error.getClass().getSimpleName();
            cfg.recordSyncError(reason);AppLogger.e(c,"ApiClient","Sync cycle failed",error);
            int attempts=cfg.incrementSyncBackoff();
            long base=error instanceof ApiFailure f&&f.status==429?Math.max(10,f.retryAfter)*1000L:30000L;
            long exponential=Math.min(15L*60*1000,base*(1L<<Math.min(5,attempts-1)));
            long jitter=java.util.concurrent.ThreadLocalRandom.current().nextLong(1000L,Math.max(1001L,base/3));
            long wait=Math.min(15L*60*1000,exponential+jitter);
            // 4xx other than 408/429 normally require operator intervention:
            // avoid battery/network hammering while surfacing the failure.
            if(error instanceof ApiFailure f && f.status>=400 && f.status<500 && f.status!=408 && f.status!=429)
                wait=Math.max(wait,5L*60*1000);
            cfg.setNextSyncAllowedAt(System.currentTimeMillis()+wait);
            SyncJobService.scheduleAfter(c,wait);
        }finally{SYNC_BUSY.set(false);}
    }
    private boolean checkServerReset()throws Exception{
        try{
            JSONObject status=request("GET","/api/v1/devices/"+cfg.deviceId()+"/lifecycle",null);
            if(status.optBoolean("resetRequired",false)){
                cfg.markRemoteResetPending();performPendingReset();return true;
            }
            return false;
        }catch(ApiFailure error){
            if(error.status==410){
                EnrollmentManager.reset(c);
                new AgentConfig(c).markRemoteResetNotified();NotificationHelper.postRemoteReset(c);
                AppLogger.i(c,"Enrollment","Previously deleted node reset locally");
                return true;
            }
            // Rolling upgrades: earlier relays do not expose lifecycle endpoint.
            if(error.status==404)return false;
            throw error;
        }
    }
    private void performPendingReset(){
        long delay=cfg.nextSyncAllowedAt()-System.currentTimeMillis();
        if(delay>0){SyncJobService.scheduleAfter(c,delay);return;}
        boolean notifyRemote=cfg.remoteResetPending();
        try{
            requireHttps(cfg.server());
            raw(cfg.server()+"/api/v1/devices/"+cfg.deviceId()+"/reset","POST",new JSONObject(),
                "Device "+cfg.deviceToken(),cfg.deviceId());
            EnrollmentManager.reset(c);
            cfg.resetSyncBackoff();
            if(notifyRemote){new AgentConfig(c).markRemoteResetNotified();NotificationHelper.postRemoteReset(c);}
            AppLogger.i(c,"Enrollment","Server confirmed reset; local data cleared");
        }catch(ApiFailure failure){
            if(failure.status==410){
                EnrollmentManager.reset(c);
                if(notifyRemote){new AgentConfig(c).markRemoteResetNotified();NotificationHelper.postRemoteReset(c);}
                AppLogger.i(c,"Enrollment","Server already deleted device; local data cleared");
                return;
            }
            retryPendingReset(failure);
        }catch(Exception failure){
            retryPendingReset(failure);
        }
    }
    private void retryPendingReset(Exception reason){
        cfg.recordSyncError("reset_pending");
        int attempts=cfg.incrementSyncBackoff();
        long delay=Math.min(15L*60*1000,15000L*(1L<<Math.min(6,attempts-1)));
        cfg.setNextSyncAllowedAt(System.currentTimeMillis()+delay);
        SyncJobService.scheduleAfter(c,delay);
        AppLogger.e(c,"Enrollment","Device unpair awaiting relay confirmation",reason);
    }
    private void rotateDeviceTokenIfNeeded()throws Exception{
        long ts=System.currentTimeMillis()/1000;
        if(cfg.tokenRotationPending()){String pending=cfg.pendingDeviceToken();if(pending==null||pending.isBlank()||cfg.pendingTokenExpiresAt()<=ts){cfg.discardPendingDeviceToken();AppLogger.w(c,"ApiClient","Expired pending device-token rotation discarded");}else{JSONObject committed=requestWithToken("POST","/api/v1/devices/"+cfg.deviceId()+"/token/commit",new JSONObject(),pending);cfg.commitDeviceToken(committed.optLong("tokenIssuedAt",ts));AppLogger.i(c,"ApiClient","Pending device-token rotation committed");return;}}
        long issued=cfg.tokenIssuedAt();if(issued>0&&ts-issued<TOKEN_ROTATE_AFTER)return;
        JSONObject prepared=request("POST","/api/v1/devices/"+cfg.deviceId()+"/token/prepare",new JSONObject());String next=prepared.getString("deviceToken");long expiresAt=prepared.optLong("expiresAt",ts+3600);cfg.stageDeviceToken(next,expiresAt);JSONObject committed=requestWithToken("POST","/api/v1/devices/"+cfg.deviceId()+"/token/commit",new JSONObject(),next);cfg.commitDeviceToken(committed.optLong("tokenIssuedAt",ts));AppLogger.i(c,"ApiClient","Device token rotated");
    }
    public void flushEvents()throws Exception{
        LocalStore store=LocalStore.get(c);int sent=0;
        for(int page=0;page<5;page++){
            List<JSONObject> pending=store.pendingEvents(BATCH_SIZE);if(pending.isEmpty())break;
            if(BATCH_SUPPORTED){
                try{
                    JSONArray items=new JSONArray();for(JSONObject e:pending)items.put(e);
                    JSONObject response=request("POST","/api/v1/devices/"+cfg.deviceId()+"/events/batch",new JSONObject().put("events",items));
                    JSONArray results=response.optJSONArray("results");
                    if(results==null)throw new IOException("Batch response missing acknowledgements");
                    java.util.Set<String> expected=new java.util.HashSet<>();
                    for(JSONObject e:pending)expected.add(e.getString("eventId"));
                    for(int i=0;i<results.length();i++){
                        JSONObject result=results.getJSONObject(i);String id=result.optString("eventId","");
                        if(!expected.remove(id)||!result.optBoolean("accepted"))throw new IOException("Invalid batch acknowledgement");
                        store.markEventSent(id);sent++;
                    }
                    if(!expected.isEmpty())throw new IOException("Incomplete batch acknowledgement");
                    continue;
                }catch(ApiFailure e){
                    if(e.status!=404)throw e;
                    BATCH_SUPPORTED=false;AppLogger.w(c,"ApiClient","Legacy Relay detected; using single event API");
                }
            }
            for(JSONObject e:pending){
                JSONObject response=request("POST","/api/v1/devices/"+cfg.deviceId()+"/events",e);
                if(response.optBoolean("accepted")){store.markEventSent(e.getString("eventId"));sent++;}
            }
        }
        if(sent>0)AppLogger.i(c,"ApiClient","Uploaded encrypted events count="+sent);
    }
    public void flushCommandAcks()throws Exception{int sent=0;for(JSONObject a:LocalStore.get(c).pendingCommandAcks(100)){String id=a.getString("commandId"),state=a.getString("state");request("POST","/api/v1/devices/"+cfg.deviceId()+"/commands/"+id+"/ack",new JSONObject().put("state",state).put("result",a.optJSONObject("result")==null?new JSONObject():a.optJSONObject("result")));LocalStore.get(c).markCommandAckSent(id,state);sent++;}if(sent>0)AppLogger.i(c,"ApiClient","Uploaded command acknowledgements count="+sent);}
    public void fetchCommands()throws Exception{JSONObject r=request("GET","/api/v1/devices/"+cfg.deviceId()+"/commands/pending?limit=50",null);JSONArray arr=r.optJSONArray("commands");if(arr!=null&&arr.length()>0)AppLogger.i(c,"ApiClient","Fetched remote commands count="+arr.length());new CommandProcessor(c,this).process(arr);}
    public void putState()throws Exception{request("POST","/api/v1/devices/"+cfg.deviceId()+"/state",StateCollector.collect(c));}
    public void heartbeat()throws Exception{request("POST","/api/v1/devices/"+cfg.deviceId()+"/heartbeat",new JSONObject().put("appVersion",BuildConfig.VERSION_NAME).put("osVersion",Build.VERSION.RELEASE));}
    public JSONObject ota()throws Exception{return request("GET","/api/v1/ota",null);}
    private JSONObject request(String method,String path,JSONObject body)throws Exception{if(!cfg.isEnrolled())throw new IllegalStateException("Not enrolled");requireHttps(cfg.server());try{return raw(cfg.server()+path,method,body,"Device "+cfg.deviceToken(),cfg.deviceId());}catch(Exception e){AppLogger.e(c,"ApiClient",method+" "+path+" failed",e);throw e;}}
    private JSONObject requestWithToken(String method,String path,JSONObject body,String token)throws Exception{if(!cfg.isEnrolled())throw new IllegalStateException("Not enrolled");requireHttps(cfg.server());try{return raw(cfg.server()+path,method,body,"Device "+token,cfg.deviceId());}catch(Exception e){AppLogger.e(c,"ApiClient",method+" "+path+" failed",e);throw e;}}
    private static JSONObject raw(String url,String method,JSONObject body,String auth,String deviceId)throws Exception{
        HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();con.setRequestMethod(method);con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setUseCaches(false);con.setRequestProperty("Accept","application/json");if(auth!=null)con.setRequestProperty("Authorization",auth);if(deviceId!=null)con.setRequestProperty("X-SimHub-Device-Id",deviceId);if(body!=null){con.setDoOutput(true);con.setRequestProperty("Content-Type","application/json");con.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=con.getResponseCode();InputStream in=code>=400?con.getErrorStream():con.getInputStream();String text=read(in);String retryAfterHeader=con.getHeaderField("Retry-After");con.disconnect();JSONObject out=text.isBlank()?new JSONObject():new JSONObject(text);if(code<200||code>=300){
            int retry=60;
            try{retry=Integer.parseInt(retryAfterHeader);}catch(Exception ignored){}
            throw new ApiFailure(code,out.optString("error","http_error"),out.optString("message","HTTP "+code),Math.max(1,Math.min(600,retry)));
        }
        return out;
    }
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8);}}
    private static void requireHttps(String server)throws Exception{URI u=URI.create(server);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null)throw new SecurityException("SIM Hub server must use HTTPS");}
}