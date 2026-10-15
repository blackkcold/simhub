package com.blackkcold.simhub.p2p;

import android.content.Context;
import com.blackkcold.simhub.SecretStore;
import org.json.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Authenticated offline double-scan. No HTTP request, Enrollment or Vault mutation. */
public final class PeerPairingManager {
    private final PeerStore store;
    private final SecretStore secrets;
    public PeerPairingManager(Context c){store=PeerStore.get(c);secrets=new SecretStore(c);}
    public String offer(String name,boolean allowSend,JSONArray endpoints)throws Exception{
        KeyPair eph=PeerCrypto.ephemeral();String sid=UUID.randomUUID().toString();long expires=System.currentTimeMillis()/1000+300;
        JSONObject body=new JSONObject().put("v",1).put("kind","offer").put("sid",sid).put("expires",expires).put("publicKey",store.publicKey()).put("ephemeral",PeerCrypto.b64(eph.getPublic().getEncoded())).put("name",name.substring(0,Math.min(name.length(),80))).put("signalingCapability",PeerCenterProtocol.MODE).put("relayHint",relayHint(store.context())).put("permissions",allowSend?"read,history,state,send":"read,history,state").put("endpoints",endpoints);
        String packed=PeerCrypto.b64(PeerCrypto.utf8(body.toString()));
        secrets.putString("p2p.pair.offer",packed);secrets.putBytes("p2p.pair.ephemeral",eph.getPrivate().getEncoded());
        return encode(packed,PeerCrypto.sign(store.identity().getPrivate(),packed));
    }
    public String respond(String qr,String name)throws Exception{
        String raw=decode(qr);JSONObject offer=new JSONObject(new String(PeerCrypto.unb64(raw),java.nio.charset.StandardCharsets.UTF_8));
        validateOffer(offer);String node=offer.getString("publicKey");if(node.equals(store.publicKey()))throw new SecurityException("cannot_pair_with_self");
        String id=PeerCrypto.digest(PeerCrypto.publicKey(node).getEncoded());requireNew(id);
        KeyPair eph=PeerCrypto.ephemeral();
        String transcript=PeerCrypto.pairTranscript(offer.getString("sid"),offer.getLong("expires"),node,offer.getString("ephemeral"),store.publicKey(),PeerCrypto.b64(eph.getPublic().getEncoded()),offer.getString("permissions"));
        byte[] shared=PeerCrypto.dh(eph.getPrivate(),PeerCrypto.publicKey(offer.getString("ephemeral")));
        byte[] root=PeerCrypto.derive(shared,PeerCrypto.utf8(PeerCrypto.digest(PeerCrypto.utf8(transcript))),"content-root");
        try{
            JSONObject p=profile(id,node,offer.optString("name","SIM Node"),"CONTROLLER",offer,"PENDING");store.save(p,root);
            JSONObject reply=new JSONObject().put("v",1).put("kind","response").put("offer",raw).put("offerHash",PeerCrypto.digest(PeerCrypto.utf8(raw))).put("publicKey",store.publicKey()).put("ephemeral",PeerCrypto.b64(eph.getPublic().getEncoded())).put("name",name.substring(0,Math.min(80,name.length())));
            String packed=PeerCrypto.b64(PeerCrypto.utf8(reply.toString()));return encode(packed,PeerCrypto.sign(store.identity().getPrivate(),packed));
        }finally{Arrays.fill(shared,(byte)0);Arrays.fill(root,(byte)0);}
    }
    public String accept(String qr)throws Exception{
        String raw=decode(qr);JSONObject response=new JSONObject(new String(PeerCrypto.unb64(raw),java.nio.charset.StandardCharsets.UTF_8));
        if(response.optInt("v")!=1||!"response".equals(response.optString("kind")))throw new SecurityException("pair_response_required");
        String packed=secrets.getString("p2p.pair.offer");byte[] der=secrets.getBytes("p2p.pair.ephemeral");
        if(packed==null||der==null||!packed.equals(response.optString("offer"))||!PeerCrypto.digest(PeerCrypto.utf8(packed)).equals(response.optString("offerHash")))throw new SecurityException("pair_session_mismatch");
        JSONObject offer=new JSONObject(new String(PeerCrypto.unb64(packed),java.nio.charset.StandardCharsets.UTF_8));validateOffer(offer);
        String controller=response.getString("publicKey"),id=PeerCrypto.digest(PeerCrypto.publicKey(controller).getEncoded());requireNew(id);
        String transcript=PeerCrypto.pairTranscript(offer.getString("sid"),offer.getLong("expires"),store.publicKey(),offer.getString("ephemeral"),controller,response.getString("ephemeral"),offer.getString("permissions"));
        PrivateKey privateKey=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        byte[] shared=PeerCrypto.dh(privateKey,PeerCrypto.publicKey(response.getString("ephemeral")));
        byte[] root=PeerCrypto.derive(shared,PeerCrypto.utf8(PeerCrypto.digest(PeerCrypto.utf8(transcript))),"content-root");
        try{store.save(profile(id,controller,response.optString("name","Controller"),"NODE",offer,"ACTIVE"),root);secrets.remove("p2p.pair.offer");secrets.remove("p2p.pair.ephemeral");return id;}finally{Arrays.fill(der,(byte)0);Arrays.fill(shared,(byte)0);Arrays.fill(root,(byte)0);}
    }
    private void requireNew(String id)throws Exception{JSONArray peers=store.list();for(int i=0;i<peers.length();i++)if(id.equals(peers.getJSONObject(i).getString("id"))&&!"REVOKED".equals(peers.getJSONObject(i).optString("pairState")))throw new SecurityException("peer_already_known_revoke_before_repair");}
    public static JSONObject relayHint(Context c)throws Exception{
        com.blackkcold.simhub.AgentConfig cfg=new com.blackkcold.simhub.AgentConfig(c);
        JSONObject hint=new JSONObject();if(cfg.isEnrolled())hint.put("origin",cfg.server()).put("deviceId",cfg.deviceId());return hint;
    }
    private JSONObject profile(String id,String pub,String name,String role,JSONObject offer,String state)throws Exception{
        return new JSONObject().put("id",id).put("publicKey",pub).put("name",name).put("localRole",role).put("permissions",offer.getString("permissions")).put("pairState",state).put("policy","P2P_ONLY").put("signalingCapability",offer.optString("signalingCapability","")).put("signalingConsent",false).put("signalingConsentRevision",0).put("signalingCenterBinding","").put("discoveryState","WAITING").put("relayHint",role.equals("CONTROLLER")?offer.optJSONObject("relayHint"):new JSONObject()).put("fallbackConsent",false).put("relayBindingState","NONE").put("activeTransport","OFFLINE").put("endpoints",role.equals("CONTROLLER")?offer.getJSONArray("endpoints"):new JSONArray());
    }
    public static String kind(String qr)throws Exception{return new JSONObject(new String(PeerCrypto.unb64(decode(qr)),java.nio.charset.StandardCharsets.UTF_8)).getString("kind");}
    private static String encode(String body,String sig)throws Exception{return "simhub-peer:"+PeerCrypto.b64(PeerCrypto.utf8(new JSONObject().put("body",body).put("signature",sig).toString()));}
    private static String decode(String qr)throws Exception{
        if(qr==null||!qr.startsWith("simhub-peer:")||qr.length()>16000)throw new SecurityException("peer_qr_invalid");
        JSONObject outer=new JSONObject(new String(PeerCrypto.unb64(qr.substring(12)),java.nio.charset.StandardCharsets.UTF_8));String body=outer.getString("body");JSONObject p=new JSONObject(new String(PeerCrypto.unb64(body),java.nio.charset.StandardCharsets.UTF_8));PeerCrypto.verify(p.getString("publicKey"),body,outer.getString("signature"));return body;
    }
    private static void validateOffer(JSONObject p)throws Exception{
        long now=System.currentTimeMillis()/1000;
        if(p.optInt("v")!=1||!"offer".equals(p.optString("kind"))||p.getLong("expires")<now||p.getLong("expires")>now+310||!p.getString("sid").matches("[a-f0-9-]{36}")||!Set.of("read,history,state","read,history,state,send").contains(p.optString("permissions")))throw new SecurityException("peer_offer_invalid_or_expired");
        PeerCrypto.publicKey(p.getString("publicKey"));PeerCrypto.publicKey(p.getString("ephemeral"));
        if(p.getJSONArray("endpoints").length()>12)throw new SecurityException("too_many_peer_endpoints");
    }
}
