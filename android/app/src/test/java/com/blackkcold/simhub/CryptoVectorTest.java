package com.blackkcold.simhub;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class CryptoVectorTest {
    @Test public void eventVectorMatchesWebCrypto() throws Exception {
        byte[] key=CryptoBox.ub64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        String kid=CryptoBox.keyId(key);
        assertEquals("Yw3NKWbEM2aRElRI",kid);

        String aad=CryptoBox.eventAad(
                "11111111-2222-3333-4444-555555555555",
                "evt-vector-1",
                "sms.received",
                1791264000L,
                "channel-vector",
                true
        );
        assertEquals("simhub-event-v2|MTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU1NTU1|ZXZ0LXZlY3Rvci0x|c21zLnJlY2VpdmVk|1791264000|Y2hhbm5lbC12ZWN0b3I|1",aad);

        byte[] iv=CryptoBox.ub64("AAECAwQFBgcICQoL");
        String plaintext="{\"direction\":\"in\",\"sender\":\"+8613800138000\",\"body\":\"Your code is 123456\",\"otp\":{\"value\":\"123456\"}}";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        assertEquals("PCCycreAoW_kLvmpi8sRA6H6pUeVFTsZSkXfpzZRNoMyKJ7MnvIqqESUXcGq5Udcl3tarwO51qgf9EV9fcOcndAN9Enn5BBDMHbFGp-tNJMa_KgOAwZxUt_MrP9MxY-vtoXaWM_xrHKT21s_BxskhYa2",CryptoBox.b64(ciphertext));

        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        assertEquals(plaintext,new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8));
    }
}
