package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local SMS status must remain readable without a Relay enrollment or Node Key. */
public final class PendingSmsCrypto {
    private static final String DOMAIN="simhub-pending-sms-v1";
    private static final String SECRET="local-pending-sms-key-v1";
    private static final String AAD="simhub-pending-sms-local-v1";
    private static final SecureRandom RANDOM=new SecureRandom();
    private PendingSmsCrypto(){}

    private static synchronized byte[] key(Context c,String name,boolean create)throws Exception{
        SecretStore secrets=new SecretStore(c);
        byte[] key=secrets.getBytes(name);
        if(key==null&&create){key=new byte[32];RANDOM.nextBytes(key);secrets.putBytes(name,key);}
        if(key==null||key.length!=32)throw new SecurityException("Local SMS key unavailable");
        return key;
    }
    public static JSONObject encrypt(Context c,JSONObject payload)throws Exception{
        byte[] key=key(c,SECRET,true);
        try{return encryptWithKey(key,payload);}
        finally{Arrays.fill(key,(byte)0);}
    }
    public static JSONObject decrypt(Context c,JSONObject envelope)throws Exception{
        String domain=envelope.optString("domain");
        if(domain.isEmpty())return new CryptoBox(c).decryptLocal(envelope);
        // Read older Dev local envelopes during the generic-helper migration.
        // No experimental transport or peer cryptography is needed by Stable.
        boolean previous="p2p-local-pending-v1".equals(domain);
        if(!DOMAIN.equals(domain)&&!previous)throw new SecurityException("Invalid local SMS envelope");
        byte[] key=key(c,previous?"p2p.pending-sms-local":SECRET,false);
        try{return decryptWithKey(key,envelope,previous?"pending-sms-local":AAD);}
        finally{Arrays.fill(key,(byte)0);}
    }
    static JSONObject encryptWithKey(byte[] key,JSONObject payload)throws Exception{
        byte[] iv=new byte[12];RANDOM.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("domain",DOMAIN).put("alg","A256GCM")
            .put("iv",CryptoBox.b64(iv)).put("ct",CryptoBox.b64(cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8))));
    }
    static JSONObject decryptWithKey(byte[] key,JSONObject envelope,String aad)throws Exception{
        byte[] iv=CryptoBox.ub64(envelope.getString("iv"));
        if(iv.length!=12)throw new SecurityException("Invalid local SMS nonce");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] clear=cipher.doFinal(CryptoBox.ub64(envelope.getString("ct")));
        try{return new JSONObject(new String(clear,StandardCharsets.UTF_8));}
        finally{Arrays.fill(clear,(byte)0);}
    }
}
