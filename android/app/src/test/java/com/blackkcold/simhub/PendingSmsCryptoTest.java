package com.blackkcold.simhub;

import org.json.JSONObject;
import org.junit.Test;
import javax.crypto.AEADBadTagException;
import static org.junit.Assert.*;

public class PendingSmsCryptoTest {
    @Test public void localEnvelopeIsIndependentAndAuthenticated()throws Exception{
        byte[] key=new byte[32];key[0]=7;
        JSONObject message=new JSONObject().put("body","synthetic SMS").put("commandId","local-test");
        JSONObject first=PendingSmsCrypto.encryptWithKey(key,message);
        JSONObject second=PendingSmsCrypto.encryptWithKey(key,message);
        assertNotEquals(first.getString("iv"),second.getString("iv"));
        assertEquals("synthetic SMS",PendingSmsCrypto.decryptWithKey(key,first,"simhub-pending-sms-local-v1").getString("body"));
        try{PendingSmsCrypto.decryptWithKey(key,first,"simhub-command-v2");fail("AAD substitution accepted");}
        catch(AEADBadTagException expected){}
        key[0]=8;
        try{PendingSmsCrypto.decryptWithKey(key,first,"simhub-pending-sms-local-v1");fail("Wrong key accepted");}
        catch(AEADBadTagException expected){}
    }
}
