package com.blackkcold.simhub;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
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
        if(!cfg.isEnrolled()||!SYNC_BUSY.compareAndSet(false,true))return;
        int pendingBefore=LocalStore.get(c).pendingEventCount();
        try{
            rotateDeviceTokenIfNeeded();LocalStore store=LocalStore.get(c);store.recoverStaleClaims();
            for(String id:store.expireStalePendingSms(48L*3600)){store.finishCommand(id,"failed");store.queueCommandAck(id,"failed",new JSONObject().put("reason","status_timeout"));}
            flushEvents();SmsHistorySync.sync(c,30);flushEvents();flushCommandAcks();fetchCommands();flushCommandAcks();putState();heartbeat();cfg.recordSyncSuccess();
            if(pendingBefore>0)AppLogger.i(c,"ApiClient","Sync cycle completed pendingBefore="+pendingBefore+" pendingAfter="+store.pendingEventCount());
        }catch(Exception error){cfg.recordSyncError(error.getClass().getSimpleName());AppLogger.e(c,"ApiClient","Sync cycle failed",error);}finally{SYNC_BUSY.set(false);}
    }
    private void rotateDeviceTokenIfNeeded()throws Exception{
        long ts=System.currentTimeMillis()/1000;
        if(cfg.tokenRotationPending()){String pending=cfg.pendingDeviceToken();if(pending==null||pending.isBlank()||cfg.pendingTokenExpiresAt()<=ts){cfg.discardPendingDeviceToken();AppLogger.w(c,"ApiClient","Expired pending device-token rotation discarded");}else{JSONObject committed=requestWithToken("POST","/api/v1/devices/"+cfg.deviceId()+"/token/commit",new JSONObject(),pending);cfg.commitDeviceToken(committed.optLong("tokenIssuedAt",ts));AppLogger.i(c,"ApiClient","Pending device-token rotation committed");return;}}
        long issued=cfg.tokenIssuedAt();if(issued>0&&ts-issued<TOKEN_ROTATE_AFTER)return;
        JSONObject prepared=request("POST","/api/v1/devices/"+cfg.deviceId()+"/token/prepare",new JSONObject());String next=prepared.getString("deviceToken");long expiresAt=prepared.optLong("expiresAt",ts+3600);cfg.stageDeviceToken(next,expiresAt);JSONObject committed=requestWithToken("POST","/api/v1/devices/"+cfg.deviceId()+"/token/commit",new JSONObject(),next);cfg.commitDeviceToken(committed.optLong("tokenIssuedAt",ts));AppLogger.i(c,"ApiClient","Device token rotated");
    }
    public void flushEvents()throws Exception{List<JSONObject> pending=LocalStore.get(c).pendingEvents(100);int sent=0;for(JSONObject e:pending){JSONObject r=request("POST","/api/v1/devices/"+cfg.deviceId()+"/events",e);if(r.optBoolean("accepted")){LocalStore.get(c).markEventSent(e.getString("eventId"));sent++;}}if(sent>0)AppLogger.i(c,"ApiClient","Uploaded encrypted events count="+sent);}
    public void flushCommandAcks()throws Exception{int sent=0;for(JSONObject a:LocalStore.get(c).pendingCommandAcks(100)){String id=a.getString("commandId"),state=a.getString("state");request("POST","/api/v1/devices/"+cfg.deviceId()+"/commands/"+id+"/ack",new JSONObject().put("state",state).put("result",a.optJSONObject("result")==null?new JSONObject():a.optJSONObject("result")));LocalStore.get(c).markCommandAckSent(id,state);sent++;}if(sent>0)AppLogger.i(c,"ApiClient","Uploaded command acknowledgements count="+sent);}
    public void fetchCommands()throws Exception{JSONObject r=request("GET","/api/v1/devices/"+cfg.deviceId()+"/commands/pending?limit=50",null);JSONArray arr=r.optJSONArray("commands");if(arr!=null&&arr.length()>0)AppLogger.i(c,"ApiClient","Fetched remote commands count="+arr.length());new CommandProcessor(c,this).process(arr);}
    public void putState()throws Exception{request("POST","/api/v1/devices/"+cfg.deviceId()+"/state",StateCollector.collect(c));}
    public void heartbeat()throws Exception{request("POST","/api/v1/devices/"+cfg.deviceId()+"/heartbeat",new JSONObject().put("appVersion",BuildConfig.VERSION_NAME).put("osVersion",Build.VERSION.RELEASE));}
    public JSONObject ota()throws Exception{return request("GET","/api/v1/ota",null);}
    private JSONObject request(String method,String path,JSONObject body)throws Exception{if(!cfg.isEnrolled())throw new IllegalStateException("Not enrolled");requireHttps(cfg.server());try{return raw(cfg.server()+path,method,body,"Device "+cfg.deviceToken(),cfg.deviceId());}catch(Exception e){AppLogger.e(c,"ApiClient",method+" "+path+" failed",e);throw e;}}
    private JSONObject requestWithToken(String method,String path,JSONObject body,String token)throws Exception{if(!cfg.isEnrolled())throw new IllegalStateException("Not enrolled");requireHttps(cfg.server());try{return raw(cfg.server()+path,method,body,"Device "+token,cfg.deviceId());}catch(Exception e){AppLogger.e(c,"ApiClient",method+" "+path+" failed",e);throw e;}}
    private static JSONObject raw(String url,String method,JSONObject body,String auth,String deviceId)throws Exception{
        HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();con.setRequestMethod(method);con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setUseCaches(false);con.setRequestProperty("Accept","application/json");if(auth!=null)con.setRequestProperty("Authorization",auth);if(deviceId!=null)con.setRequestProperty("X-SimHub-Device-Id",deviceId);if(body!=null){con.setDoOutput(true);con.setRequestProperty("Content-Type","application/json");con.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=con.getResponseCode();InputStream in=code>=400?con.getErrorStream():con.getInputStream();String text=read(in);con.disconnect();JSONObject out=text.isBlank()?new JSONObject():new JSONObject(text);if(code<200||code>=300)throw new IllegalStateException(out.optString("message","HTTP "+code));return out;
    }
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8);}}
    private static void requireHttps(String server)throws Exception{URI u=URI.create(server);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null)throw new SecurityException("SIM Hub server must use HTTPS");}
}