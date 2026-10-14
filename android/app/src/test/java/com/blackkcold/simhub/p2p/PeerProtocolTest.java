package com.blackkcold.simhub.p2p;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeerProtocolTest {
    private final byte[] key=PeerCrypto.random(32);
    private final String a="sandbox-controller-123456789",b="sandbox-node-123456789",id="sandbox-command-123456789";
    @Test public void verifiesIdentityExpiryAndTamper()throws Exception{
        JSONObject frame=PeerProtocol.seal(key,a,b,id,1100,new JSONObject().put("action","device.state"));
        assertEquals("device.state",PeerProtocol.open(key,a,b,frame,1000).getString("action"));
        expectReject(()->PeerProtocol.open(key,b,a,frame,1000));
        expectReject(()->PeerProtocol.open(key,a,b,frame,1101));
        expectReject(()->PeerProtocol.open(key,a,b,frame,700));
        JSONObject forged=new JSONObject(frame.toString());forged.put("id","sandbox-forged-command-123456789");expectReject(()->PeerProtocol.open(key,a,b,forged,1000));
    }
    @Test public void neverAcceptsLegacyVaultDomain()throws Exception{
        JSONObject frame=PeerProtocol.seal(key,a,b,id,1100,new JSONObject().put("action","device.state"));frame.put("domain","simhub-event-v1");expectReject(()->PeerProtocol.open(key,a,b,frame,1000));
    }
    private interface Operation{void run()throws Exception;}
    private static void expectReject(Operation operation)throws Exception{try{operation.run();fail("expected rejection");}catch(SecurityException|java.security.GeneralSecurityException expected){}}
}
