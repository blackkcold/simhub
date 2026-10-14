package com.blackkcold.simhub.p2p;

import android.content.Context;
import android.net.nsd.*;
import org.json.*;

/** Discovery supplies endpoint hints only; it can never create a grant or change keys. */
public final class PeerDiscovery implements AutoCloseable {
    private final NsdManager manager;
    private final PeerStore store;
    private boolean started;
    private boolean resolving;
    private final NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
        public void onDiscoveryStarted(String type){} public void onDiscoveryStopped(String type){}
        public void onStartDiscoveryFailed(String type,int code){started=false;}
        public void onStopDiscoveryFailed(String type,int code){started=false;}
        public void onServiceLost(NsdServiceInfo info){}
        public void onServiceFound(NsdServiceInfo info){
            try{
                JSONArray peers=store.list();for(int i=0;i<peers.length();i++){
                    JSONObject p=peers.getJSONObject(i);
                    if(!"REVOKED".equals(p.optString("pairState"))&&"CONTROLLER".equals(p.optString("localRole"))&&info.getServiceName().startsWith("simhub_"+p.getString("id").substring(0,16)))resolve(info,p.getString("id"));
                }
            }catch(Exception ignored){}
        }
    };
    public PeerDiscovery(Context c){manager=c.getSystemService(NsdManager.class);store=PeerStore.get(c);}
    public void start(){if(!started){manager.discoverServices("_simhub-peer._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);started=true;}}
    private synchronized void resolve(NsdServiceInfo info,String id){
        if(resolving)return;resolving=true;
        manager.resolveService(info,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo i,int e){resolving=false;}
            public void onServiceResolved(NsdServiceInfo i){resolving=false;try{
                JSONObject p=store.peer(id);if("REVOKED".equals(p.optString("pairState")))return;
                JSONArray endpoints=new JSONArray().put(new JSONObject().put("host",i.getHost().getHostAddress()).put("port",i.getPort()));
                p.put("endpoints",endpoints);store.update(p);
            }catch(Exception ignored){}}
        });
    }
    public void close(){if(started)try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}started=false;}
}
