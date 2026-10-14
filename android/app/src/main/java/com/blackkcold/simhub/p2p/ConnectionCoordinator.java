package com.blackkcold.simhub.p2p;

import org.json.JSONObject;

/** All fallbacks retain the same request ID and authorization domain. */
public final class ConnectionCoordinator {
    private final PeerStore store;
    private HubTransport internet;
    private HubTransport mailbox;
    public ConnectionCoordinator(PeerStore store){this.store=store;}
    public void internet(HubTransport value){internet=value;}
    public void mailbox(HubTransport value){mailbox=value;}
    public JSONObject request(String peerId,String requestId,JSONObject request)throws Exception{
        JSONObject peer=store.peer(peerId);if("REVOKED".equals(peer.optString("pairState")))throw new SecurityException("peer_revoked");
        ConnectionPolicy policy=ConnectionPolicy.valueOf(peer.getString("policy"));
        if(policy==ConnectionPolicy.RELAY_ONLY)throw new SecurityException("use_legacy_relay_workspace");
        Exception failed;
        try{return accept(peer,new DirectPeerTransport(store),requestId,request);}catch(Exception e){if(e instanceof SecurityException)throw e;failed=e;}
        if(internet!=null&&policy.permits(internet.route(),peer.optBoolean("fallbackConsent"),"ACTIVE".equals(peer.optString("relayBindingState")))){
            try{return accept(peer,internet,requestId,request);}catch(Exception e){if(e instanceof SecurityException)throw e;failed=e;}
        }
        if(mailbox!=null&&policy.permits("MAILBOX",peer.optBoolean("fallbackConsent"),"ACTIVE".equals(peer.optString("relayBindingState"))))return accept(peer,mailbox,requestId,request);
        peer.put("activeTransport","OFFLINE");store.update(peer);throw failed;
    }
    private JSONObject accept(JSONObject peer,HubTransport transport,String id,JSONObject req)throws Exception{
        JSONObject result=transport.request(peer,id,req);peer=store.peer(peer.getString("id"));if("REVOKED".equals(peer.optString("pairState")))throw new SecurityException("peer_revoked");
        peer.put("activeTransport",transport.route()).put("lastSyncAt",System.currentTimeMillis()/1000);if("PENDING".equals(peer.optString("pairState")))peer.put("pairState","ACTIVE");store.update(peer);return result;
    }
}
