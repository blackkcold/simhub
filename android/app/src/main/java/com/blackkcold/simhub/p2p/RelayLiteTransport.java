package com.blackkcold.simhub.p2p;

import android.content.Context;
import com.blackkcold.simhub.SecretStore;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.UUID;

/** One explicitly selected peer can carry opaque offline ciphertext to a target. */
public final class RelayLiteTransport implements HubTransport {
    private final Context context;
    private final PeerStore store;
    private final String relayId;
    private RelayLiteTransport(Context context,PeerStore store,String relayId){
        this.context=context.getApplicationContext();this.store=store;this.relayId=relayId;
    }
    public String route(){return "RELAY_LITE";}

    public static RelayLiteTransport configured(Context c,PeerStore store,JSONObject target){
        if(!target.optBoolean("relayLiteConfigured",false))return null;
        String relay=target.optString("relayLiteVia","");
        if(relay.isEmpty()||relay.equals(target.optString("id")))return null;
        try{
            JSONObject profile=store.peer(relay);
            if(!"ACTIVE".equals(profile.optString("pairState")))return null;
            return new RelayLiteTransport(c,store,relay);
        }catch(Exception missing){return null;}
    }

    /** No implicit Relay enrollment or multi-hop: use a fresh direct/RTC route to C. */
    static JSONObject requestRelay(Context c,PeerStore store,String relay,String id,JSONObject body)throws Exception{
        JSONObject profile=store.peer(relay);
        if(!"ACTIVE".equals(profile.optString("pairState")))throw new SecurityException("relay_lite_peer_not_active");
        ConnectionCoordinator hop=new ConnectionCoordinator(store);
        if(profile.optBoolean("signalingConsent")){
            try{
                PeerCenterConnector.ensure(c.getApplicationContext(),relay);
                PeerCenterConnector.active(relay).route(); // retain only authenticated channel
                hop.internet(PeerCenterConnector.active(relay));
            }catch(Exception ignored){/* LAN may still be available. */}
        }
        return hop.request(relay,id,body);
    }

    public JSONObject request(JSONObject target,String id,JSONObject request)throws Exception{
        String targetId=target.getString("id"),sourceId=store.myId();
        JSONObject previouslyDelivered=RelayLiteClient.cachedResult(context,targetId,id);
        if(previouslyDelivered!=null)return previouslyDelivered;
        String action=request.getString("action");
        if(!PeerGrantPolicy.allows(target,action,false))
            throw new SecurityException("relay_lite_target_grant_missing");
        // These status/command requests are asynchronous. The app must not
        // pretend a relay acceptance means the target executed the command.
        long now=System.currentTimeMillis()/1000;
        long expiry=now+PeerOfflineProtocol.MAX_TTL_SECONDS;
        if("sms.send".equals(action))
            expiry=Math.min(expiry,request.optLong("commandExpires",now));
        if(expiry<=now)throw new SecurityException("relay_lite_command_expired");
        byte[] root=store.key(targetId);
        final JSONObject encrypted;
        try{
            encrypted=PeerOfflineProtocol.seal(root,sourceId,targetId,id,now,expiry,request);
        }finally{Arrays.fill(root,(byte)0);}
        JSONObject packet=new JSONObject().put("action","relay.put")
            .put("target",targetId).put("frame",encrypted);
        JSONObject result=requestRelay(context,store,relayId,UUID.randomUUID().toString(),packet);
        if(!"accepted".equals(result.optString("state")))
            throw new IllegalStateException("relay_lite_enqueue_unconfirmed");
        return new JSONObject().put("state","queued").put("route","RELAY_LITE")
            .put("requestId",id).put("relayAccepted",true);
    }
}
