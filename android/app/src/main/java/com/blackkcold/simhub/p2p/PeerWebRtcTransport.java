package com.blackkcold.simhub.p2p;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** System WebView's maintained ICE/DTLS engine. Only bundled static content can execute. */
public final class PeerWebRtcTransport implements HubTransport,AutoCloseable {
    public interface Signals{void send(JSONObject payload);}
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private final PeerStore store;
    private final JSONObject profile;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CountDownLatch loaded=new CountDownLatch(1);
    private volatile CompletableFuture<Void> connected=new CompletableFuture<>();
    private final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final ExecutorService dispatch=Executors.newSingleThreadExecutor();
    private final Signals signals;
    private final boolean consent;
    private WebView view;
    private volatile String route="DIRECT";
    private volatile boolean closed;
    private volatile boolean online;
    public PeerWebRtcTransport(Context c,JSONObject profile,boolean consent,Signals signals)throws Exception{this.store=PeerStore.get(c);this.profile=profile;this.consent=consent;this.signals=signals;CompletableFuture<Void> ready=new CompletableFuture<>();main.post(()->{try{create(c);ready.complete(null);}catch(Exception e){ready.completeExceptionally(e);}});ready.get(10,TimeUnit.SECONDS);}
    @SuppressLint("SetJavaScriptEnabled") private void create(Context c){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw new UnsupportedOperationException("update_android_system_webview");
        view=new WebView(c);WebSettings settings=view.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setBlockNetworkLoads(true);
        view.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if((ORIGIN+"/p2p.html").equals(r.getUrl().toString()))try{return new WebResourceResponse("text/html","UTF-8",c.getAssets().open("p2p.html"));}catch(IOException ignored){}
                return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }});
        WebViewCompat.addWebMessageListener(view,"PeerBridge",Set.of(ORIGIN),(v,message,source,mainFrame,reply)->{
            if(!mainFrame||!ORIGIN.equals(source.toString().replaceAll("/$","")))return;String data=message.getData();if(data==null||data.length()>800_000)return;
            dispatch.execute(()->handle(data));
        });view.loadUrl(ORIGIN+"/p2p.html");
    }
    private void handle(String raw){try{
        JSONObject msg=new JSONObject(raw);switch(msg.getString("type")){
            case "loaded" -> loaded.countDown();
            case "connected" -> {String selected=msg.getString("route");if("TURN".equals(selected)&&!consent){close();return;}route=selected;online=true;connected.complete(null);}
            case "signal" -> signals.send(msg.getJSONObject("payload"));
            case "data" -> incoming(new JSONObject(msg.getString("frame")));
            case "closed","error" -> {online=false;if(connected.isDone())connected=new CompletableFuture<>();for(CompletableFuture<JSONObject> f:pending.values())f.completeExceptionally(new IOException("peer_channel_closed"));}
            default -> {}
        }
    }catch(Exception ignored){/* Content and crypto failures are never logged. */}}
    public void init(JSONArray servers,boolean initiator)throws Exception{if(!loaded.await(10,TimeUnit.SECONDS))throw new IOException("peer_webview_not_ready");command(new JSONObject().put("type","init").put("servers",servers).put("fallback",consent).put("initiator",initiator));}
    public void signal(JSONObject payload)throws Exception{command(new JSONObject().put("type","signal").put("payload",payload));}
    private void command(JSONObject cmd){main.post(()->{if(!closed&&view!=null)view.evaluateJavascript("window.peerCommand("+cmd.toString()+")",null);});}
    public String route(){return route;}
    public boolean online(){return online;}
    public void restart()throws Exception{command(new JSONObject().put("type","restart"));}
    public JSONObject request(JSONObject peer,String id,JSONObject req)throws Exception{
        try{connected.get(8,TimeUnit.SECONDS);}catch(TimeoutException e){throw new IOException("peer_ice_not_connected",e);}
        if(closed||!online)throw new IOException("peer_ice_not_connected");
        JSONObject current=store.peer(peer.getString("id"));
        if(!ConnectionPolicy.valueOf(current.getString("policy")).permits(route,current.optBoolean("fallbackConsent"),"ACTIVE".equals(current.optString("relayBindingState"))))throw new SecurityException("peer_route_not_authorized");
        CompletableFuture<JSONObject> future=new CompletableFuture<>();if(pending.putIfAbsent(id,future)!=null)throw new IllegalStateException("request_already_pending");byte[] key=store.key(peer.getString("id"));
        try{JSONObject frame=PeerProtocol.seal(key,store.myId(),peer.getString("id"),id,System.currentTimeMillis()/1000+120,req);command(new JSONObject().put("type","send").put("frame",frame.toString()));return future.get(20,TimeUnit.SECONDS);}finally{pending.remove(id);Arrays.fill(key,(byte)0);}
    }
    private void incoming(JSONObject frame)throws Exception{
        if(!online)throw new SecurityException("peer_route_unconfirmed");
        String peerId=profile.getString("id");JSONObject current=store.peer(peerId);byte[] key=store.key(peerId);
        try{
            JSONObject plaintext=PeerProtocol.open(key,peerId,store.myId(),frame,System.currentTimeMillis()/1000);String id=frame.getString("id");
            if("CONTROLLER".equals(current.optString("localRole"))){CompletableFuture<JSONObject> f=pending.get(id);if(f!=null)f.complete(plaintext);}
            else{
                if("TURN".equals(route)&&(!current.optBoolean("fallbackConsent")||!"P2P_RELAY_FALLBACK".equals(current.optString("policy"))))throw new SecurityException("peer_fallback_not_allowed");
                JSONObject result=new PeerRpc(store).dispatch(peerId,id,plaintext);store.markTransport(peerId,route);JSONObject response=PeerProtocol.seal(key,store.myId(),peerId,id,frame.getLong("expires"),result);command(new JSONObject().put("type","send").put("frame",response.toString()));
            }
        }finally{Arrays.fill(key,(byte)0);}
    }
    public void close(){closed=true;online=false;dispatch.shutdownNow();for(CompletableFuture<JSONObject> f:pending.values())f.completeExceptionally(new IOException("peer_transport_closed"));pending.clear();main.post(()->{if(view!=null){view.destroy();view=null;}});}
}
