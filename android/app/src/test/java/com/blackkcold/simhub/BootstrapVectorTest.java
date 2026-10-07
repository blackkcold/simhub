package com.blackkcold.simhub;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapVectorTest {
    @Test public void decryptsSharedBootstrapVector() throws Exception {
        byte[] bootstrap=CryptoBox.ub64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        JSONObject envelope=new JSONObject()
                .put("v",1)
                .put("alg","A256GCM")
                .put("iv","AAECAwQFBgcICQoL")
                .put("ct","ZyP0OOHA5DylaL2gncRWQrPntQfETmlLAF7fviFUPo0MKRF4hxylstWkdoiMOKmW");
        byte[] raw=CryptoBox.decryptBootstrapNodeKey(envelope,bootstrap,"ctu3M2x2eAAj-D2k");
        assertEquals("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",CryptoBox.b64(raw));
    }
}
