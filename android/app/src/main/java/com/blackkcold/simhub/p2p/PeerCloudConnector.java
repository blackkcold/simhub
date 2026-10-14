package com.blackkcold.simhub.p2p;

import android.content.Context;
import okhttp3.*;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Automatic public signaling, STUN-only ICE. Never opens TURN or a data mailbox. */
public final class PeerCloudConnector implements HubTransport,AutoCloseable {
    private static final Map<String,PeerCloudConnector> ACTIVE=new ConcurrentHashMap<>();
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build();
    public static PeerCloudConnector active(String id){return ACTIVE.get(id);}
    /** A signaling socket can be READY before an authenticated ICE data channel exists. */
    public static boolean dataChannelOnline(String id){PeerCloudConnector c=ACTIVE.get(id);PeerWebRtcTransport t=c==null?null:c.rtc;return t!=null&&t.online();}
    public static synchronized void ensure(Context c,String id)throws Exception{
        if(!ACTIVE.containsKey(id)){PeerCloudConnector connector=new PeerCloudConnector(c,id);ACTIVE.put(id,connector);connector.start();}
    }
    public static void closeAll(){for(PeerCloudConnector c:new ArrayList<>(ACTIVE.values()))c.close();}
    public static void closePeer(String id){PeerCloudConnector c=ACTIVE.get(id);if(c!=null)c.close();}
    public static void networkChangedAll(){for(PeerCloudConnector c:ACTIVE.values())c.networkChanged();}
    /** User-requested reconnect affects only the selected device. */
    public static void reconnect(String id){PeerCloudConnector c=ACTIVE.get(id);if(c!=null)c.networkChanged();}
    private final PeerStore store;
    private final String peerId,token=UUID.randomUUID().toString();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Set<String> seen=new LinkedHashSet<>();
    private volatile PeerWebRtcTransport rtc;
    private volatile WebSocket socket;
    private volatile boolean closed,opened;
    private long nextConnect,lastOffer;
    private int failures;
    private PeerCloudConnector(Context c,String id){store=PeerStore.get(c);peerId=id;}
    public HubTransport transport(){return rtc;}
    public String route(){return "DIRECT";}
    public JSONObject request(JSONObject peer,String id,JSONObject request)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!closed&&rtc==null&&System.nanoTime()<deadline)Thread.sleep(100);
        PeerWebRtcTransport current=rtc;if(closed||current==null)throw new IOException("public_peer_unavailable");
        return current.request(peer,id,request);
    }
    private void start(){worker.scheduleWithFixedDelay(this::tick,0,5,TimeUnit.SECONDS);}
    private boolean allowed()throws Exception{JSONObject p=store.peer(peerId);return !"REVOKED".equals(p.optString("pairState"))&&p.optBoolean("publicDiscoveryEnabled");}
    private void tick(){if(closed)return;try{
        if(!allowed()){close();return;}long now=System.currentTimeMillis()/1000;
        if(socket==null&&now>=nextConnect)open();
        if(opened&&socket!=null){socket.send("{\"type\":\"HEARTBEAT\"}");
            if("CONTROLLER".equals(store.peer(peerId).optString("localRole"))&&rtc!=null&&!rtc.online()&&now-lastOffer>=15){lastOffer=now;rtc.restart();}}
    }catch(Exception e){status("UNAVAILABLE","public_connection_unavailable");}}
    private void open()throws Exception{
        status("CONNECTING","");byte[] key=store.key(peerId);String room;
        try{room=PeerCloudProtocol.room(key,store.myId());}finally{Arrays.fill(key,(byte)0);}
        Request request=new Request.Builder().url(PeerCloudProtocol.SOCKET+"?key=peerjs&id="+room+"&token="+token+"&version=1.5.5").build();
        socket=HTTP.newWebSocket(request,new WebSocketListener(){
            public void onMessage(WebSocket ws,String text){if(text.length()>PeerCloudProtocol.MAX_SIGNAL){ws.cancel();return;}submit(()->receive(ws,text));}
            public void onFailure(WebSocket ws,Throwable t,Response r){submit(()->lost(ws));}
            public void onClosed(WebSocket ws,int code,String reason){submit(()->lost(ws));}
            public void onClosing(WebSocket ws,int code,String reason){ws.close(code,null);}
        });
    }
    private void submit(Runnable task){if(!closed)try{worker.execute(task);}catch(RejectedExecutionException ignored){}}
    private void receive(WebSocket ws,String raw){if(closed||ws!=socket)return;try{
        if(!allowed()){close();return;}JSONObject message=new JSONObject(raw);String type=message.getString("type");
        if("OPEN".equals(type)){opened=true;failures=0;status("READY","");
            if(rtc==null){JSONObject profile=store.peer(peerId);rtc=new PeerWebRtcTransport(store.context(),profile,false,signal->submit(()->send(signal)));rtc.init(PeerCloudProtocol.iceServers(),"CONTROLLER".equals(profile.optString("localRole")));lastOffer=System.currentTimeMillis()/1000;}
            else if(!rtc.online()&&"CONTROLLER".equals(store.peer(peerId).optString("localRole")))rtc.restart();return;}
        if(Set.of("ERROR","ID-TAKEN","INVALID-KEY").contains(type)){ws.cancel();lost(ws);return;}
        if(!Set.of("OFFER","ANSWER","CANDIDATE").contains(type)||rtc==null)return;
        byte[] key=store.key(peerId);JSONObject signal;
        try{signal=PeerCloudProtocol.incoming(key,peerId,store.myId(),message,System.currentTimeMillis()/1000);}finally{Arrays.fill(key,(byte)0);}
        String id=message.getJSONObject("payload").getJSONObject("metadata").getJSONObject("simhub").getString("id");if(!seen.add(id))return;
        if(seen.size()>512)seen.remove(seen.iterator().next());rtc.signal(signal);
    }catch(Exception ignored){/* An untrusted signal never changes grants or executes an RPC. */}}
    private void send(JSONObject signal){try{if(!opened||socket==null||!allowed())return;byte[] key=store.key(peerId);
        try{if(!socket.send(PeerCloudProtocol.outgoing(key,store.myId(),peerId,signal,System.currentTimeMillis()/1000).toString()))lost(socket);}finally{Arrays.fill(key,(byte)0);}
    }catch(Exception ignored){}}
    private void lost(WebSocket ws){if(ws!=socket)return;socket=null;opened=false;nextConnect=System.currentTimeMillis()/1000+Math.min(60,1L<<Math.min(++failures,5));status("UNAVAILABLE","public_signaling_unavailable");}
    public void networkChanged(){submit(()->{WebSocket old=socket;if(old!=null)old.cancel();socket=null;opened=false;nextConnect=0;lastOffer=0;if(rtc!=null){rtc.close();rtc=null;}tick();});}
    private void status(String state,String error){try{synchronized(store){JSONObject p=store.peer(peerId);p.put("discoveryState",state).put("connectionError",error);store.update(p);}}catch(Exception ignored){}}
    public void close(){if(closed)return;closed=true;ACTIVE.remove(peerId,this);WebSocket old=socket;socket=null;if(old!=null)old.cancel();if(rtc!=null)rtc.close();worker.shutdownNow();status("STOPPED","");}
}
