package com.blackkcold.simhub.p2p;

import org.json.*;
import org.junit.Test;
import javax.crypto.AEADBadTagException;
import static org.junit.Assert.*;

public class PeerCloudProtocolTest {
    private static final long NOW=2_000_000_000L;
    private JSONObject offer()throws Exception{return new JSONObject().put("description",new JSONObject().put("type","offer").put("sdp","v=0\r\na=fingerprint:sha-256 TEST\r\n"));}
    private JSONObject wire(byte[] key)throws Exception{return PeerCloudProtocol.outgoing(key,"node-sandbox-identity000","controller-sandbox-identity000",offer(),NOW).put("src",PeerCloudProtocol.room(key,"node-sandbox-identity000"));}
    @Test public void opaqueRoomsArePairAndDirectionScoped()throws Exception{
        byte[] a=PeerCrypto.random(32),b=PeerCrypto.random(32);String id=PeerCloudProtocol.room(a,"node-sandbox-identity000");
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));assertEquals(id,PeerCloudProtocol.room(a,"node-sandbox-identity000"));
        assertNotEquals(id,PeerCloudProtocol.room(a,"controller-sandbox-identity000"));assertNotEquals(id,PeerCloudProtocol.room(b,"node-sandbox-identity000"));
    }
    @Test public void signalsRoundTripWithoutPlainSdp()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key);assertFalse(w.toString().contains("fingerprint"));assertEquals(offer().toString(),PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW).toString());}
    @Test public void refusesRpcInSignaling()throws Exception{assertThrows(SecurityException.class,()->PeerCloudProtocol.outgoing(PeerCrypto.random(32),"node-sandbox-identity000","controller-sandbox-identity000",new JSONObject().put("action","sms.send"),NOW));}
    @Test public void refusesWrongSource()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key).put("src","attacker");assertThrows(SecurityException.class,()->PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW));}
    @Test public void refusesWrongDestination()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key).put("dst","other");assertThrows(SecurityException.class,()->PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW));}
    @Test public void refusesExpiredSignal()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key);assertThrows(SecurityException.class,()->PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW+61));}
    @Test public void refusesChangedOuterType()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key).put("type","ANSWER");assertThrows(SecurityException.class,()->PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW));}
    @Test public void refusesTamperedCipher()throws Exception{byte[] key=PeerCrypto.random(32);JSONObject w=wire(key);JSONObject frame=w.getJSONObject("payload").getJSONObject("metadata").getJSONObject("simhub");byte[] ct=PeerCrypto.unb64(frame.getString("ct"));ct[0]^=1;frame.put("ct",PeerCrypto.b64(ct));assertThrows(AEADBadTagException.class,()->PeerCloudProtocol.incoming(key,"node-sandbox-identity000","controller-sandbox-identity000",w,NOW));}
    @Test public void defaultIceContainsNoTurn()throws Exception{String ice=PeerCloudProtocol.iceServers().toString();assertTrue(ice.contains("stun:"));assertFalse(ice.contains("turn:"));assertFalse(ice.contains("turns:"));}
}
