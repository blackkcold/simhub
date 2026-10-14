package com.blackkcold.simhub.p2p;

import org.json.*;
import java.util.*;

/** PeerServer is an untrusted signaling carrier. No RPC or SMS is sent to it. */
public final class PeerCloudProtocol {
    public static final String SOCKET="wss://0.peerjs.com/peerjs";
    public static final String MODE="PUBLIC_DIRECT_V1";
    public static final int MAX_SIGNAL=65536;
    private PeerCloudProtocol(){}
    public static String room(byte[] key,String identity)throws Exception {
        byte[] derived=PeerCrypto.derive(key,PeerCrypto.utf8("simhub-discovery-v1"),"peerjs-room|"+identity);
        try{StringBuilder hex=new StringBuilder("sh");for(byte b:derived)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}finally{Arrays.fill(derived,(byte)0);}
    }
    public static JSONArray iceServers()throws JSONException {
        return new JSONArray().put(new JSONObject().put("urls",new JSONArray()
            .put("stun:stun.l.google.com:19302").put("stun:stun.cloudflare.com:3478")));
    }
    public static String type(JSONObject signal)throws JSONException {
        JSONObject description=signal.optJSONObject("description");
        if(description!=null){String type=description.getString("type");
            if(!Set.of("offer","answer").contains(type)||description.getString("sdp").length()>40000)throw new SecurityException("invalid_peer_description");
            return type.equals("offer")?"OFFER":"ANSWER";
        }
        if(signal.optJSONObject("candidate")!=null&&signal.toString().length()<4096)return "CANDIDATE";
        throw new SecurityException("only_peer_signaling_allowed");
    }
    public static JSONObject outgoing(byte[] key,String from,String to,JSONObject signal,long now)throws Exception {
        String kind=type(signal),id=UUID.randomUUID().toString();
        JSONObject frame=PeerProtocol.seal(key,room(key,from),room(key,to),id,now+60,new JSONObject().put("action","signal").put("payload",signal));
        JSONObject message=new JSONObject().put("type",kind).put("dst",room(key,to)).put("payload",
            new JSONObject().put("type","data").put("connectionId","simhub-p2p-v1").put("simhub",frame));
        if(message.toString().length()>MAX_SIGNAL)throw new SecurityException("signal_too_large");
        return message;
    }
    public static JSONObject incoming(byte[] key,String from,String to,JSONObject message,long now)throws Exception {
        if(message.toString().length()>MAX_SIGNAL||!room(key,from).equals(message.optString("src"))||
            !room(key,to).equals(message.optString("dst")))throw new SecurityException("wrong_signal_route");
        JSONObject payload=PeerProtocol.open(key,room(key,from),room(key,to),message.getJSONObject("payload").getJSONObject("simhub"),now);
        if(!"signal".equals(payload.optString("action")))throw new SecurityException("only_peer_signaling_allowed");
        JSONObject signal=payload.getJSONObject("payload");if(!type(signal).equals(message.getString("type")))throw new SecurityException("signal_type_mismatch");
        return signal;
    }
}
