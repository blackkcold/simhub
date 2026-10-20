package com.blackkcold.simhub.p2p;

import android.content.Context;
import android.net.nsd.*;
import org.json.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * NSD is a hint only. Each resolved endpoint is authenticated by PeerProtocol
 * before any business RPC; discovery never confers access or changes a key.
 *
 * Own this from the foreground PeerService, not only from PeerActivity.
 * Android NSD implementations can reject concurrent resolveService calls;
 * serialize them rather than dropping peers discovered while resolving.
 */
public final class PeerDiscovery implements AutoCloseable {
    private static final class Pending {
        final NsdServiceInfo info;
        final String peerId;
        Pending(NsdServiceInfo info, String peerId) { this.info=info; this.peerId=peerId; }
    }
    private final NsdManager manager;
    private final PeerStore store;
    private final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private final Set<String> queuedPeers=new HashSet<>();
    private boolean started;
    private boolean resolving;

    private final NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
        public void onDiscoveryStarted(String type){}
        public void onDiscoveryStopped(String type){}
        public void onStartDiscoveryFailed(String type,int code){synchronized(PeerDiscovery.this){started=false;}}
        public void onStopDiscoveryFailed(String type,int code){synchronized(PeerDiscovery.this){started=false;}}
        public void onServiceLost(NsdServiceInfo info){}
        public void onServiceFound(NsdServiceInfo info){
            try{
                JSONArray peers=store.list();
                for(int i=0;i<peers.length();i++){
                    JSONObject p=peers.getJSONObject(i);
                    String id=p.getString("id");
                    // v2 peers are symmetric; a former NODE also discovers
                    // the other peer's listener when reverse grants exist.
                    boolean clientSide="CONTROLLER".equals(p.optString("localRole"))
                        ||(p.optInt("protocolVersion",1)>=2
                            &&!"".equals(p.optString("grantsFromRemote","")));
                    if(!"REVOKED".equals(p.optString("pairState"))
                        &&clientSide
                        &&info.getServiceName().startsWith("simhub_"+id.substring(0,16))){
                        enqueue(info,id);
                    }
                }
            }catch(Exception ignored){/* Discovery cannot change grants. */}
        }
    };

    public PeerDiscovery(Context c){manager=c.getSystemService(NsdManager.class);store=PeerStore.get(c);}
    public synchronized void start(){
        if(started)return;
        // Mark as active before starting, so a synchronous failure can be retried.
        started=true;
        try{manager.discoverServices("_simhub-peer._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);}
        catch(RuntimeException error){started=false;throw error;}
    }

    private void enqueue(NsdServiceInfo info,String id){
        synchronized(this){
            if(!started||!queuedPeers.add(id))return;
            pending.addLast(new Pending(info,id));
        }
        resolveNext();
    }

    private void resolveNext(){
        final Pending next;
        synchronized(this){
            if(!started||resolving)return;
            next=pending.pollFirst();
            if(next==null)return;
            resolving=true;
        }
        try{
            manager.resolveService(next.info,new NsdManager.ResolveListener(){
                public void onResolveFailed(NsdServiceInfo service,int code){finish(next.peerId);}
                public void onServiceResolved(NsdServiceInfo service){
                    try{
                        if(service.getHost()==null||service.getPort()<1024||service.getPort()>65535)return;
                        synchronized(PeerDiscovery.this){if(!started)return;}
                        JSONObject peer=store.peer(next.peerId);
                        if("REVOKED".equals(peer.optString("pairState")))return;
                        JSONArray endpoints=new JSONArray().put(new JSONObject()
                            .put("host",service.getHost().getHostAddress()).put("port",service.getPort()));
                        peer.put("endpoints",endpoints);
                        store.update(peer);
                    }catch(Exception ignored){/* Do not persist an unverified endpoint. */}
                    finally{finish(next.peerId);}
                }
            });
        }catch(RuntimeException error){finish(next.peerId);}
    }

    private void finish(String id){
        synchronized(this){queuedPeers.remove(id);resolving=false;}
        resolveNext();
    }

    public synchronized void close(){
        started=false;
        pending.clear();
        queuedPeers.clear();
        if(resolving){/* In-flight callbacks may arrive; they check started below. */}
        try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}
    }
}
