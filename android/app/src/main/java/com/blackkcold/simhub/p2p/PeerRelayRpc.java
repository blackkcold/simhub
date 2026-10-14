package com.blackkcold.simhub.p2p;

import android.content.Context;
import org.json.*;
import java.util.Arrays;

/** Relay enrollment authorizes the carrier only; the P2P grant authorizes RPC. */
public final class PeerRelayRpc {
    private PeerRelayRpc(){}
    public static JSONObject execute(Context c,JSONObject frame)throws Exception{
        PeerStore store=PeerStore.get(c);String sender=frame.getString("from"),id=frame.getString("id");byte[] key=store.key(sender);
        try{
            JSONObject request=PeerProtocol.open(key,sender,store.myId(),frame,System.currentTimeMillis()/1000);
            JSONObject result=new PeerRpc(store).dispatch(sender,id,request);store.markTransport(sender,"LEGACY_RELAY");
            return PeerProtocol.seal(key,store.myId(),sender,id,frame.getLong("expires"),new JSONObject()
                .put("requestDigest",PeerCrypto.digest(PeerCrypto.utf8(request.toString()))).put("result",result));
        }finally{Arrays.fill(key,(byte)0);}
    }
}
