package com.blackkcold.simhub.p2p;

import org.junit.Test;
import org.json.JSONObject;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class PeerOfflineProtocolTest {
    private static final String A="0123456789abcdef0123456789abcdef";
    private static final String B="abcdef0123456789abcdef0123456789";
    @Test public void storeAndForwardSurvivesOfflineWindowWithoutExposingContent()throws Exception{
        byte[] key=PeerCrypto.random(32);long now=1800000000L,exp=now+86400;
        JSONObject event=new JSONObject().put("action","sms.event").put("body","confidential otp 777777");
        JSONObject envelope=PeerOfflineProtocol.seal(key,A,B,"message012345678901",now,exp,event);
        assertFalse(envelope.toString().contains("777777"));
        assertEquals(event.toString(),PeerOfflineProtocol.open(key,A,B,envelope,now+50000).toString());
        assertThrows(SecurityException.class,()->PeerOfflineProtocol.open(key,A,B,envelope,exp));
        assertThrows(SecurityException.class,()->PeerOfflineProtocol.open(key,B,A,envelope,now));
        Arrays.fill(key,(byte)0);
    }
    @Test public void domainKeyAndBindingDoNotDowngradeToLiveRpc()throws Exception{
        byte[] key=PeerCrypto.random(32);long now=1800000000L;
        JSONObject envelope=PeerOfflineProtocol.seal(key,A,B,"message012345678901",now,now+3600,new JSONObject().put("action","device.state"));
        JSONObject changed=new JSONObject(envelope.toString()).put("id","message012345678902");
        assertThrows(Exception.class,()->PeerOfflineProtocol.open(key,A,B,changed,now));
        assertThrows(SecurityException.class,()->PeerProtocol.open(key,A,B,envelope,now));
        assertThrows(SecurityException.class,()->PeerOfflineProtocol.seal(key,A,B,"message012345678901",now,now+PeerOfflineProtocol.MAX_TTL_SECONDS+1,new JSONObject()));
        Arrays.fill(key,(byte)0);
    }
}
