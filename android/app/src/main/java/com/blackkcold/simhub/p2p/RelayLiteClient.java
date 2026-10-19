package com.blackkcold.simhub.p2p;

import android.content.Context;
import com.blackkcold.simhub.SecretStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded poll-and-ACK worker, run only for explicitly configured relay inboxes. */
public final class RelayLiteClient {
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private RelayLiteClient(){}
    private static String responseKey(String from,String id){return "p2p.relayLite.response."+from+"."+id;}
    private static String sentKey(String from,String id){return "p2p.relayLite.sent."+from+"."+id;}

    /** A manual sync never silently enables any network path or SMS permission. */
    public static int pollAll(Context context){
        if(!RUNNING.compareAndSet(false,true))return 0;
        try{
            PeerStore store=PeerStore.get(context);
            JSONArray profiles=store.list();int processed=0;
            for(int i=0;i<profiles.length();i++){
                JSONObject relay=profiles.optJSONObject(i);
                if(relay==null||!relay.optBoolean("relayLiteInbox",false)
                    ||!"ACTIVE".equals(relay.optString("pairState")))continue;
                try{processed+=poll(context,store,relay.getString("id"));}
                catch(Exception ignored){/* Retain unacknowledged ciphertext for a later retry. */}
            }
            return processed;
        }finally{RUNNING.set(false);}
    }

    private static int poll(Context c,PeerStore store,String relayId)throws Exception{
        String localId=store.myId();int processed=0;
        for(int batch=0;batch<4;batch++){
            JSONObject response=RelayLiteTransport.requestRelay(c,store,relayId,
                UUID.randomUUID().toString(),new JSONObject().put("action","relay.pull").put("limit",2));
            JSONArray items=response.optJSONArray("items");
            if(items==null||items.length()==0)break;
            for(int i=0;i<items.length();i++){
                JSONObject row=items.getJSONObject(i);
                String from=row.getString("source"),id=row.getString("id");
                JSONObject frame=row.getJSONObject("frame");
                try{
                    process(c,store,relayId,from,localId,id,frame);
                    RelayLiteTransport.requestRelay(c,store,relayId,UUID.randomUUID().toString(),
                        new JSONObject().put("action","relay.ack").put("source",from).put("messageId",id));
                    processed++;
                }catch(Exception invalidOrOffline){
                    // A failure is not a positive delivery ACK. The bounded TTL
                    // and queue limits prevent permanent resource exhaustion.
                }
            }
            if(items.length()<2)break;
        }
        return processed;
    }

    private static void process(Context c,PeerStore store,String relayId,String from,
                                String localId,String id,JSONObject frame)throws Exception{
        JSONObject source=store.peer(from);
        if(!"ACTIVE".equals(source.optString("pairState"))
            &&!(source.optInt("protocolVersion",1)>=2&&"PENDING".equals(source.optString("pairState"))))
            throw new SecurityException("relay_lite_source_revoked");
        if(!id.equals(frame.getString("id")))throw new SecurityException("relay_lite_id_mismatch");
        byte[] root=store.key(from);
        final JSONObject decrypted;
        try{decrypted=PeerOfflineProtocol.open(root,from,localId,frame,System.currentTimeMillis()/1000);}
        finally{Arrays.fill(root,(byte)0);}
        SecretStore secrets=new SecretStore(c);
        if("relay.response".equals(decrypted.optString("action"))){
            JSONObject result=decrypted.getJSONObject("result");
            // Persist locally under the encrypted Android secret store before ACK.
            secrets.putString(responseKey(from,id),result.toString());
            String state=result.optString("state");
            if("submitted".equals(state)||"succeeded".equals(state)||"failed".equals(state)
                ||"delivered".equals(state)||"expired".equals(state))store.clearOutbound(from,id);
            return;
        }
        String stored=secrets.getString(sentKey(from,id));
        JSONObject responseFrame;
        if(stored!=null){
            responseFrame=new JSONObject(stored);
        }else{
            JSONObject result;
            try{result=new PeerRpc(store).dispatch(from,id,decrypted);}
            catch(SecurityException rejected){result=new JSONObject().put("state","failed").put("reason","relay_lite_peer_permission_denied");}
            // A response may be larger than one bounded offline frame. Fail closed
            // with a small explicit error rather than misrepresenting delivery.
            long now=System.currentTimeMillis()/1000;
            byte[] contentRoot=store.key(from);
            try{
                JSONObject responsePayload=new JSONObject().put("action","relay.response").put("result",result);
                try{responseFrame=PeerOfflineProtocol.seal(contentRoot,localId,from,id,now,
                    now+PeerOfflineProtocol.MAX_TTL_SECONDS,responsePayload);}
                catch(SecurityException tooLarge){
                    responseFrame=PeerOfflineProtocol.seal(contentRoot,localId,from,id,now,
                        now+PeerOfflineProtocol.MAX_TTL_SECONDS,
                        new JSONObject().put("action","relay.response").put("result",
                            new JSONObject().put("state","failed").put("reason","relay_lite_response_too_large")));
                }
            }finally{Arrays.fill(contentRoot,(byte)0);}
            secrets.putString(sentKey(from,id),responseFrame.toString());
        }
        JSONObject accepted=RelayLiteTransport.requestRelay(c,store,relayId,
            UUID.randomUUID().toString(),new JSONObject().put("action","relay.put")
                .put("target",from).put("frame",responseFrame));
        if(!"accepted".equals(accepted.optString("state")))
            throw new IllegalStateException("relay_lite_response_unconfirmed");
    }

    public static JSONObject cachedResult(Context c,String peerId,String requestId){
        try{
            String saved=new SecretStore(c).getString(responseKey(peerId,requestId));
            return saved==null?null:new JSONObject(saved);
        }catch(Exception unavailable){return null;}
    }
}
