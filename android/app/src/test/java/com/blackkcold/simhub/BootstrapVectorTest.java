package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapVectorTest {
    @Test public void decryptsSharedBootstrapVector() throws Exception {
        byte[] bootstrap=CryptoBox.ub64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        assertEquals("Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0",CryptoBox.bootstrapProof(bootstrap));
        byte[] raw=CryptoBox.decryptBootstrapNodeKey(
                "AAECAwQFBgcICQoL",
                "ZyP0OOHA5DylaL2gncRWQrPntQfETmlLAF7fviFUPo0MKRF4hxylstWkdoiMOKmW",
                bootstrap,
                "ctu3M2x2eAAj-D2k"
        );
        assertEquals("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",CryptoBox.b64(raw));
    }
}
