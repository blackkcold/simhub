package com.blackkcold.simhub.p2p;

import android.content.Context;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, replaceable connection service. It never calls Legacy Relay APIs. */
public final class PeerInternetConnector implements AutoCloseable {
    private static final Map<String,PeerInternetConnector> ACTIVE=new ConcurrentHashMap<>();
    public static PeerInternetConnector active(String id){return ACTIVE.get(id);}
    public static void closeAll(){for(PeerInternetConnector c:new ArrayList<>(ACTIVE.values()))c.close();}
    private final PeerStore store;
    private final String peerId;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,CompletableFuture<JSONObject>> pendingMailbox=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private String origin,token;
    private PeerWebRtcTransport rtc;
    private boolean fallback;
    private long tokenExpires,lastRestart;
    public PeerInternetConnector(Context c,String peerId){store=PeerStore.get(c);this.peerId=peerId;}
    public void connect(String url,boolean fallback)throws Exception{
        this.fallback=fallback;origin=requireOrigin(url);
        JSONObject profile=store.peer(peerId);if("REVOKED".equals(profile.optString("pairState")))throw new SecurityException("peer_revoked");
        JSONObject challenge=http("GET","/api/v2/p2p/challenge",null);
        String policy=fallback?"P2P_RELAY_FALLBACK":"P2P_ONLY",challengeId=challenge.getString("challenge");
        String transcript="register|"+challengeId+"|"+peerId+"|"+policy+"|"+fallback+"|"+store.myId();
        JSONObject response=http("POST","/api/v2/p2p/register",new JSONObject().put("identity",store.publicKey()).put("peer",peerId).put("policy",policy).put("fallbackConsent",fallback).put("challenge",challengeId).put("signature",PeerCrypto.sign(store.identity().getPrivate(),transcript)));
        token=response.getString("token");tokenExpires=response.getLong("expires");profile.put("policy",policy).put("fallbackConsent",fallback).put("relayBindingState","ACTIVE").put("connectionService",origin).put("connectionEnabled",true);store.update(profile);
        JSONArray servers=response.optJSONArray("iceServers");if(servers==null)servers=new JSONArray();
        rtc=new PeerWebRtcTransport(store.context(),profile,fallback,payload->{if(!closed)worker.execute(()->{try{send("signal",UUID.randomUUID().toString(),new JSONObject().put("action","signal").put("payload",payload));}catch(Exception ignored){}});});
        rtc.init(servers,"CONTROLLER".equals(profile.optString("localRole")));
        PeerInternetConnector previous=ACTIVE.put(peerId,this);if(previous!=null&&previous!=this)previous.close();
        new Thread(this::poll,"simhub-peer-connect-poll").start();
    }
    public HubTransport transport(String id){return id.equals(peerId)?rtc:null;}
    public HubTransport mailbox(String id){if(!fallback||!id.equals(peerId))return null;return new HubTransport(){public String route(){return "MAILBOX";}public JSONObject request(JSONObject p,String requestId,JSONObject request)throws Exception{
        if(closed)throw new IOException("mailbox_closed");CompletableFuture<JSONObject> f=new CompletableFuture<>();if(pendingMailbox.putIfAbsent(requestId,f)!=null)throw new IllegalStateException("mailbox_request_already_pending");try{send("mailbox",requestId,request);return f.get(25,TimeUnit.SECONDS);}finally{pendingMailbox.remove(requestId);}
    }};}
    private void send(String type,String id,JSONObject payload)throws Exception{
        JSONObject peer=store.peer(peerId);if("REVOKED".equals(peer.optString("pairState")))throw new SecurityException("peer_revoked");
        if("mailbox".equals(type)&&(!fallback||!peer.optBoolean("fallbackConsent")||!"P2P_RELAY_FALLBACK".equals(peer.optString("policy"))))throw new SecurityException("mailbox_not_authorized");
        byte[] key=store.key(peerId);try{JSONObject envelope=PeerProtocol.seal(key,store.myId(),peerId,id,System.currentTimeMillis()/1000+120,payload);http("POST","/api/v2/p2p/message",new JSONObject().put("kind",type).put("peer",peerId).put("frame",envelope));}finally{Arrays.fill(key,(byte)0);}
    }
    private void poll(){long cursor=0;while(!closed){try{
        JSONObject response=http("GET","/api/v2/p2p/poll?after="+cursor,null);JSONArray rows=response.getJSONArray("messages");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);JSONObject current=store.peer(peerId);if("REVOKED".equals(current.optString("pairState"))){close();return;}
            JSONObject frame=row.getJSONObject("frame");byte[] key=store.key(peerId);JSONObject payload;
            try{payload=PeerProtocol.open(key,peerId,store.myId(),frame,System.currentTimeMillis()/1000);}finally{Arrays.fill(key,(byte)0);}
            String id=frame.getString("id");
            if("signal".equals(row.getString("kind"))&&"signal".equals(payload.optString("action")))rtc.signal(payload.getJSONObject("payload"));
            else if("mailbox".equals(row.getString("kind"))){
                if(!fallback||!current.optBoolean("fallbackConsent"))throw new SecurityException("mailbox_not_authorized");
                if("CONTROLLER".equals(current.optString("localRole"))){CompletableFuture<JSONObject> f=pendingMailbox.get(id);if(f!=null)f.complete(payload);}
                else{JSONObject result=new PeerRpc(store).dispatch(peerId,id,payload);store.markTransport(peerId,"MAILBOX");send("mailbox",id,result);}
            }
            // The server keeps data until a successfully authenticated frame is processed.
            http("POST","/api/v2/p2p/ack",new JSONObject().put("seq",row.getLong("seq")));cursor=row.getLong("seq");
        }
        if(rtc!=null){
            JSONObject current=store.peer(peerId);long seen=current.optLong("lastSyncAt");String reported=System.currentTimeMillis()/1000-seen<30?current.optString("activeTransport","OFFLINE"):(rtc.online()?rtc.route():"OFFLINE");
            http("POST","/api/v2/p2p/status",new JSONObject().put("activeTransport",reported));
            long now=System.currentTimeMillis()/1000;
            if(!rtc.online()&&now-lastRestart>25&&"CONTROLLER".equals(store.peer(peerId).optString("localRole"))){lastRestart=now;rtc.restart();}
            if(now+60>tokenExpires){renew();}
        }
        if(rows.length()==0)Thread.sleep(1500);
    }catch(Exception e){try{Thread.sleep(3000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}}}
    private JSONObject http(String method,String path,JSONObject body)throws Exception{
        HttpsURLConnection con=(HttpsURLConnection)new URL(origin+path).openConnection();con.setInstanceFollowRedirects(false);con.setConnectTimeout(8000);con.setReadTimeout(12000);con.setRequestMethod(method);
        try{
            if(token!=null)con.setRequestProperty("Authorization","Bearer "+token);con.setRequestProperty("Content-Type","application/json");
            if(body!=null){byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);if(b.length>800_000)throw new SecurityException("connect_request_too_large");con.setDoOutput(true);con.setFixedLengthStreamingMode(b.length);try(OutputStream out=con.getOutputStream()){out.write(b);}}
            int status=con.getResponseCode();if(status<200||status>=300)throw new IOException("connect_service_http_"+status);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=con.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2_000_000)throw new SecurityException("connect_response_too_large");bytes.write(buffer,0,n);}}
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8));
        }finally{con.disconnect();}
    }
    private static String requireOrigin(String url){URI uri=URI.create(url.trim());if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!uri.getPath().equals("/")))throw new SecurityException("connection_service_https_origin_required");return url.trim().replaceAll("/+$","");}
    private void renew()throws Exception{
        JSONObject profile=store.peer(peerId);JSONObject challenge=http("GET","/api/v2/p2p/challenge",null);String challengeId=challenge.getString("challenge");String policy=profile.getString("policy");boolean consent=profile.optBoolean("fallbackConsent");
        String transcript="register|"+challengeId+"|"+peerId+"|"+policy+"|"+consent+"|"+store.myId();
        JSONObject response=http("POST","/api/v2/p2p/register",new JSONObject().put("identity",store.publicKey()).put("peer",peerId).put("policy",policy).put("fallbackConsent",consent).put("challenge",challengeId).put("signature",PeerCrypto.sign(store.identity().getPrivate(),transcript)));
        token=response.getString("token");tokenExpires=response.getLong("expires");
    }
    public void close(){closed=true;ACTIVE.remove(peerId,this);worker.shutdownNow();if(rtc!=null)rtc.close();for(CompletableFuture<JSONObject> f:pendingMailbox.values())f.completeExceptionally(new IOException("mailbox_closed"));pendingMailbox.clear();token=null;}
}
