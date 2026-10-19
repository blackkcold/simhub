package com.blackkcold.simhub.p2p;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Distinct end-to-end encrypted store-and-forward envelope. The intermediate
 * Relay Lite only carries opaque frames: it never derives the content key.
 * Offline envelopes intentionally have a separate key/domain/TTL from live RPC.
 */
public final class PeerOfflineProtocol {
    private PeerOfflineProtocol(){}
    public static final String DOMAIN="simhub-peer-offline-v1";
    public static final long MAX_TTL_SECONDS=72L*3600;
    public static final int MAX_FRAME_BYTES=64*1024;

    private static void requireId(String s){
        if(s==null||!s.matches("[A-Za-z0-9_-]{16,80}"))
            throw new SecurityException("offline_peer_identifier_invalid");
    }
    private static String aad(String from,String to,String id,long exp){
        return DOMAIN+"|"+from+"|"+to+"|"+id+"|"+exp;
    }
    private static byte[] offlineKey(byte[] contentRoot)throws Exception{
        if(contentRoot==null||contentRoot.length!=32)throw new SecurityException("offline_key_invalid");
        return PeerCrypto.derive(contentRoot,PeerCrypto.utf8(DOMAIN),"offline-message");
    }
    public static JSONObject seal(byte[] contentRoot,String from,String to,String id,long now,long expires,
                                  JSONObject payload)throws Exception{
        requireId(from);requireId(to);requireId(id);
        if(from.equals(to)||expires<=now||expires>now+MAX_TTL_SECONDS)
            throw new SecurityException("offline_expiry_invalid");
        byte[] key=offlineKey(contentRoot),nonce=PeerCrypto.random(12);
        try{
            byte[] ct=PeerCrypto.crypt(true,key,nonce,aad(from,to,id,expires),PeerCrypto.utf8(payload.toString()));
            JSONObject frame=new JSONObject().put("v",1).put("domain",DOMAIN)
                .put("from",from).put("to",to).put("id",id).put("expires",expires)
                .put("iv",PeerCrypto.b64(nonce)).put("ct",PeerCrypto.b64(ct));
            if(frame.toString().getBytes(StandardCharsets.UTF_8).length>MAX_FRAME_BYTES)
                throw new SecurityException("offline_frame_too_large");
            return frame;
        }finally{Arrays.fill(key,(byte)0);Arrays.fill(nonce,(byte)0);}
    }
    public static JSONObject open(byte[] contentRoot,String from,String to,JSONObject frame,long now)throws Exception{
        requireId(from);requireId(to);
        if(frame==null||frame.toString().getBytes(StandardCharsets.UTF_8).length>MAX_FRAME_BYTES
            ||frame.optInt("v")!=1||!DOMAIN.equals(frame.optString("domain"))
            ||!from.equals(frame.optString("from"))||!to.equals(frame.optString("to")))
            throw new SecurityException("offline_domain_mismatch");
        String id=frame.getString("id");requireId(id);
        long exp=frame.getLong("expires");
        if(exp<=now||exp>now+MAX_TTL_SECONDS)
            throw new SecurityException("offline_frame_expired");
        byte[] key=offlineKey(contentRoot);
        try{
            return new JSONObject(new String(PeerCrypto.crypt(false,key,
                PeerCrypto.unb64(frame.getString("iv")),aad(from,to,id,exp),
                PeerCrypto.unb64(frame.getString("ct"))),StandardCharsets.UTF_8));
        }finally{Arrays.fill(key,(byte)0);}
    }
}
