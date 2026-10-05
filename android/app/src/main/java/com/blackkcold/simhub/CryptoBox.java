package com.blackkcold.simhub;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoBox {
    public static final String AAD_EVENT_V1 = "simhub-event-v1";
    public static final String AAD_COMMAND_V1 = "simhub-command-v1";
    private static final String AAD_LOCAL = "simhub-local-pending-sms-v1";
    private final byte[] masterKey;
    private final byte[] deviceKey;
    private final String deviceId;
    private final String kid;
    private final SecureRandom random = new SecureRandom();

    public CryptoBox(Context c) {
        AgentConfig cfg = new AgentConfig(c);
        masterKey = cfg.vaultKey();
        deviceId = cfg.deviceId();
        if(masterKey==null || masterKey.length!=32 || deviceId==null || deviceId.isBlank()) throw new IllegalStateException("Device is not enrolled");
        try {
            deviceKey = deriveDeviceKey(masterKey, deviceId);
            kid = keyId(deviceKey);
        } catch(Exception e) {
            throw new IllegalStateException("Unable to derive device key", e);
        }
    }

    public JSONObject encryptEvent(JSONObject obj,String eventId,String kind,long occurredAt,String subId,boolean hasOtp)throws Exception{
        return encryptV2(obj, deviceKey, eventAad(deviceId,eventId,kind,occurredAt,subId,hasOtp));
    }

    public JSONObject decryptCommand(JSONObject envelope,String commandId,String type,long createdAt,long expiresAt,String idempotencyKey)throws Exception{
        int v=envelope.optInt("v");
        if(v==1) return decryptWith(envelope,masterKey,AAD_COMMAND_V1);
        if(v!=2 || !"A256GCM".equals(envelope.optString("alg"))) throw new SecurityException("Unsupported envelope");
        if(!kid.equals(envelope.optString("kid"))) throw new SecurityException("Key id mismatch");
        return decryptWith(envelope,deviceKey,commandAad(deviceId,commandId,type,createdAt,expiresAt,idempotencyKey));
    }

    public JSONObject encryptLocal(JSONObject obj)throws Exception{
        return encryptV2(obj,deviceKey,AAD_LOCAL);
    }

    public JSONObject decryptLocal(JSONObject envelope)throws Exception{
        int v=envelope.optInt("v");
        if(v==1) return decryptWith(envelope,masterKey,AAD_EVENT_V1);
        if(v!=2 || !kid.equals(envelope.optString("kid"))) throw new SecurityException("Local key mismatch");
        return decryptWith(envelope,deviceKey,AAD_LOCAL);
    }

    private JSONObject encryptV2(JSONObject obj,byte[] key,String aad)throws Exception{
        byte[] iv=new byte[12];random.nextBytes(iv);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct=c.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v",2).put("alg","A256GCM").put("kid",kid).put("iv",b64(iv)).put("ct",b64(ct));
    }

    private static JSONObject decryptWith(JSONObject envelope,byte[] key,String aad)throws Exception{
        if(!"A256GCM".equals(envelope.optString("alg"))) throw new SecurityException("Unsupported cipher");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ub64(envelope.getString("iv"))));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(c.doFinal(ub64(envelope.getString("ct"))),StandardCharsets.UTF_8));
    }

    public static byte[] deriveDeviceKey(byte[] master,String deviceId)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("simhub-device-v1".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] prk=mac.doFinal(master);
        mac.init(new SecretKeySpec(prk,"HmacSHA256"));
        mac.update(deviceId.getBytes(StandardCharsets.UTF_8));
        mac.update((byte)1);
        return Arrays.copyOf(mac.doFinal(),32);
    }

    public static String keyId(byte[] key)throws Exception{
        return b64(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(key),12));
    }

    private static String field(String s){return b64((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    public static String eventAad(String deviceId,String eventId,String kind,long occurredAt,String subId,boolean hasOtp){
        return "simhub-event-v2|"+field(deviceId)+"|"+field(eventId)+"|"+field(kind)+"|"+occurredAt+"|"+field(subId)+"|"+(hasOtp?"1":"0");
    }
    public static String commandAad(String deviceId,String commandId,String type,long createdAt,long expiresAt,String idempotencyKey){
        return "simhub-command-v2|"+field(deviceId)+"|"+field(commandId)+"|"+field(type)+"|"+createdAt+"|"+expiresAt+"|"+field(idempotencyKey);
    }
    public static String b64(byte[] b){return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    public static byte[] ub64(String s){return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
}
