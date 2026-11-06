package com.blackkcold.simhub.p2p;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded poll-and-ACK worker, run only for explicitly configured relay inboxes. */
public final class RelayLiteClient {
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private RelayLiteClient(){}
    static final class RejectedEnvelope extends Exception {
        final String reason;
        RejectedEnvelope(String reason){super(reason);this.reason=reason;}
    }

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
                JSONObject row=items.optJSONObject(i);
                if(row==null)continue;
                String from=row.optString("source"),id=row.optString("id");
                try{
                    process(c,store,relayId,from,localId,id,row.optJSONObject("frame"));
                    RelayLiteTransport.requestRelay(c,store,relayId,UUID.randomUUID().toString(),
                        new JSONObject().put("action","relay.ack").put("source",from).put("messageId",id));
                    processed++;
                }catch(RejectedEnvelope rejected){
                    try{RelayLiteTransport.requestRelay(c,store,relayId,UUID.randomUUID().toString(),
                        new JSONObject().put("action","relay.reject").put("source",from)
                            .put("messageId",id).put("reason",rejected.reason));}
                    catch(Exception offlineOrOlderRelay){/* No positive delivery ACK; retry after the mailbox lease. */}
                }catch(Exception transientFailure){
                    // Network, storage and Keystore availability failures remain
                    // retryable. The mailbox lease allows other sources to run.
                }
            }
        }
        return processed;
    }

    private static void process(Context c,PeerStore store,String relayId,String from,
                                String localId,String id,JSONObject frame)throws Exception{
        PreparedResponse response=prepareResponse(c,store,from,localId,id,frame);
        if(response==null)return;
        publishResponse(store,response,responseFrame->RelayLiteTransport.requestRelay(c,store,relayId,
            UUID.randomUUID().toString(),new JSONObject().put("action","relay.put")
                .put("target",from).put("frame",responseFrame)));
    }
    @FunctionalInterface interface ResponsePublisher {JSONObject publish(JSONObject frame)throws Exception;}
    static final class PreparedResponse {
        final String peer,generation;final JSONObject frame;
        PreparedResponse(String peer,String generation,JSONObject frame){this.peer=peer;this.generation=generation;this.frame=frame;}
    }
    static void publishResponse(PeerStore store,PreparedResponse response,ResponsePublisher publisher)throws Exception{
        // Validate the source generation at publication, not only at decrypt.
        // A completed revoke prevents acquisition; an existing lease drains
        // before revoke reports completion, without holding the store lock.
        try(PeerStore.Publication ignored=store.beginPublication(response.peer,response.generation)){
            JSONObject accepted=publisher.publish(response.frame);
            if(!"accepted".equals(accepted.optString("state")))
                throw new IllegalStateException("relay_lite_response_unconfirmed");
        }
    }
    static PreparedResponse prepareResponse(Context c,PeerStore store,String from,
                                String localId,String id,JSONObject frame)throws Exception{
        final JSONObject responseFrame;
        final String generation;
        synchronized(store){
            JSONObject source;
            try{source=store.peer(from);}
            catch(SecurityException missing){throw new RejectedEnvelope("source_revoked");}
            if(!"ACTIVE".equals(source.optString("pairState"))
                &&!(source.optInt("protocolVersion",1)>=2&&"PENDING".equals(source.optString("pairState"))))
                throw new RejectedEnvelope("source_revoked");
            if(frame==null||!id.equals(frame.optString("id")))throw new RejectedEnvelope("invalid_envelope");
            byte[] root=store.key(from);
            try{
                generation=PeerCrypto.digest(root);
                long now=System.currentTimeMillis()/1000;
                final JSONObject decrypted;
                try{decrypted=PeerOfflineProtocol.open(root,from,localId,frame,now);}
                catch(SecurityException|IllegalArgumentException|org.json.JSONException|javax.crypto.BadPaddingException invalid){
                    throw new RejectedEnvelope("invalid_envelope");
                }
                String action=decrypted.optString("action");
                if(action.isEmpty()||("relay.response".equals(action)&&decrypted.optJSONObject("result")==null))
                    throw new RejectedEnvelope("invalid_envelope");
                RelayLiteCache cache=RelayLiteCache.get(c);
                String digest=PeerCrypto.digest(PeerCrypto.utf8(frame.toString()));
                if("relay.response".equals(action)){
                    JSONObject result=decrypted.getJSONObject("result");
                    boolean protect=result.has("commandId")||store.outbound(from,id)!=null;
                    cache.claim(from,root,id,"response",digest,frame.getLong("expires"),protect,now);
                    // Persist pair-encrypted result before ACK or clearing intent.
                    cache.complete(from,root,id,"response",result);
                    store.clearLiteOutbound(from,id);
                    String state=result.optString("state");
                    if("submitted".equals(state)||"succeeded".equals(state)||"failed".equals(state)
                        ||"delivered".equals(state)||"expired".equals(state))store.clearOutbound(from,id);
                    return null;
                }
                boolean protect=!java.util.Arrays.asList("device.state","sms.page","events.page","command.status").contains(action);
                RelayLiteCache.Entry previous=cache.claim(from,root,id,"sent",digest,frame.getLong("expires"),protect,now);
                if(previous!=null){
                    // The tombstone survives payload eviction or a crash after
                    // claim. Never turn an uncertain prior dispatch into a resend.
                    if(previous.value==null)throw new RejectedEnvelope("result_unavailable");
                    responseFrame=previous.value;
                }else{
                    JSONObject legacy=cache.legacy(from,id,"sent",now);
                    if(legacy!=null)responseFrame=legacy;
                    else{
                        JSONObject result;
                        try{
                            if("sms.page".equals(action)||"events.page".equals(action)){
                                decrypted.put("limit",Math.min(10,decrypted.optInt("limit",100)));
                                decrypted.put("maxBytes",32000);
                            }
                            result=new PeerRpc(store).dispatch(from,id,decrypted);
                        }catch(SecurityException rejected){result=new JSONObject().put("state","failed").put("reason","relay_lite_peer_permission_denied");}
                        responseFrame=sealResponse(root,localId,from,id,now,result);
                    }
                    cache.complete(from,root,id,"sent",responseFrame);
                }
            }finally{Arrays.fill(root,(byte)0);}
        }
        return new PreparedResponse(from,generation,responseFrame);
    }
    private static JSONObject sealResponse(byte[] root,String localId,String from,String id,long now,JSONObject result)throws Exception{
        try{return PeerOfflineProtocol.seal(root,localId,from,id,now,now+PeerOfflineProtocol.MAX_TTL_SECONDS,
            new JSONObject().put("action","relay.response").put("result",result));}
        catch(SecurityException tooLarge){return PeerOfflineProtocol.seal(root,localId,from,id,now,
            now+PeerOfflineProtocol.MAX_TTL_SECONDS,new JSONObject().put("action","relay.response")
                .put("result",new JSONObject().put("state","failed").put("reason","relay_lite_response_too_large")));}
    }
    public static JSONObject cachedResult(Context c,String peerId,String requestId){
        try{
            PeerStore store=PeerStore.get(c);
            synchronized(store){
                byte[] root=store.key(peerId);
                try{
                    long now=System.currentTimeMillis()/1000;RelayLiteCache cache=RelayLiteCache.get(c);
                    RelayLiteCache.Entry saved=cache.get(peerId,root,requestId,"response",now);
                    if(saved!=null)return saved.value!=null?saved.value:unavailable();
                    JSONObject legacy=cache.legacy(peerId,requestId,"response",now);
                    if(legacy==null)return null;
                    cache.claim(peerId,root,requestId,"response","",now+PeerOfflineProtocol.MAX_TTL_SECONDS,true,now);
                    cache.complete(peerId,root,requestId,"response",legacy);
                    return legacy;
                }finally{Arrays.fill(root,(byte)0);}
            }
        }catch(Exception failure){try{return unavailable();}catch(Exception ignored){return null;}}
    }
    private static JSONObject unavailable()throws Exception{
        return new JSONObject().put("state","indeterminate").put("reason","relay_lite_result_unavailable");
    }
}
