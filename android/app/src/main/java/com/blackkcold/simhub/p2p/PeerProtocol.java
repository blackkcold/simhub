package com.blackkcold.simhub.p2p;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Authenticated identities, route-independent request IDs and strict protocol domain. */
public final class PeerProtocol {
    public static final int MAX_FRAME=600_000;
    public static String aad(String from,String to,String id,long expires){return from+"|"+to+"|"+id+"|"+expires;}
    public static JSONObject seal(byte[] key,String from,String to,String id,long expires,JSONObject payload)throws Exception{
        requireId(from);requireId(to);requireId(id);
        byte[] nonce=PeerCrypto.random(12);
        byte[] data=PeerCrypto.crypt(true,key,nonce,aad(from,to,id,expires),PeerCrypto.utf8(payload.toString()));
        JSONObject o=new JSONObject().put("v",1).put("domain",PeerCrypto.DOMAIN).put("from",from).put("to",to).put("id",id).put("expires",expires).put("iv",PeerCrypto.b64(nonce)).put("ct",PeerCrypto.b64(data));
        if(o.toString().length()>MAX_FRAME)throw new SecurityException("peer_frame_too_large");return o;
    }
    public static JSONObject open(byte[] key,String from,String to,JSONObject frame,long now)throws Exception{
        if(frame.toString().length()>MAX_FRAME||frame.optInt("v")!=1||!PeerCrypto.DOMAIN.equals(frame.optString("domain"))||!from.equals(frame.optString("from"))||!to.equals(frame.optString("to")))throw new SecurityException("peer_domain_mismatch");
        String id=frame.getString("id");requireId(id);long exp=frame.getLong("expires");
        if(exp<now||exp>now+300)throw new SecurityException("peer_frame_expired");
        return new JSONObject(new String(PeerCrypto.crypt(false,key,PeerCrypto.unb64(frame.getString("iv")),aad(from,to,id,exp),PeerCrypto.unb64(frame.getString("ct"))),StandardCharsets.UTF_8));
    }
    private static void requireId(String s){if(s==null||!s.matches("[A-Za-z0-9_-]{16,80}"))throw new SecurityException("peer_identifier_invalid");}
    private PeerProtocol(){}
}
