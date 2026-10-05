package com.blackkcold.simhub;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoBox {
    public static final String AAD_EVENT = "simhub-event-v1";
    public static final String AAD_COMMAND = "simhub-command-v1";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public CryptoBox(Context c) { key = new AgentConfig(c).vaultKey(); if(key==null||key.length!=32) throw new IllegalStateException("Device is not enrolled"); }
    public JSONObject encrypt(JSONObject obj, String aad) throws Exception {
        byte[] iv=new byte[12]; random.nextBytes(iv);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct=c.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v",1).put("alg","A256GCM").put("iv",b64(iv)).put("ct",b64(ct));
    }
    public JSONObject decrypt(JSONObject envelope, String aad) throws Exception {
        if(envelope.optInt("v")!=1||!"A256GCM".equals(envelope.optString("alg"))) throw new SecurityException("Unsupported envelope");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ub64(envelope.getString("iv")))); c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(c.doFinal(ub64(envelope.getString("ct"))),StandardCharsets.UTF_8));
    }
    public static String b64(byte[] b){return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}    
    public static byte[] ub64(String s){return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
}
