package com.blackkcold.simhub;

import android.content.Context;
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
    private static final String AAD_LOCAL = "simhub-local-pending-sms-v2";
    private final byte[] legacyMasterKey;
    private final byte[] trafficKey;
    private final byte[] localKey;
    private final String deviceId;
    private final String kid;
    private final String localKid;
    private final SecureRandom random = new SecureRandom();

    public CryptoBox(Context c) {
        AgentConfig cfg = new AgentConfig(c);
        legacyMasterKey = cfg.vaultKey();
        byte[] nodeKey=cfg.nodeKey();
        deviceId = cfg.deviceId();
        if(deviceId==null || deviceId.isBlank()) throw new IllegalStateException("Device is not enrolled");
        try {
            if(nodeKey!=null&&nodeKey.length==32){
                trafficKey=Arrays.copyOf(nodeKey,nodeKey.length);
                kid=keyId(trafficKey);
                if(!cfg.nodeKeyId().isBlank()&&!cfg.nodeKeyId().equals(kid))throw new SecurityException("Stored node key id mismatch");
            }else{
                if(legacyMasterKey==null||legacyMasterKey.length!=32)throw new IllegalStateException("No traffic key is available");
                trafficKey=deriveDeviceKey(legacyMasterKey,deviceId);
                kid=keyId(trafficKey);
            }
            localKey=cfg.localQueueKey();
            localKid=keyId(localKey);
        } catch(Exception e) {
            throw new IllegalStateException("Unable to initialize cryptography", e);
        }
    }

    public String keyId(){return kid;}

    public JSONObject encryptEvent(JSONObject obj,String eventId,String kind,long occurredAt,String subId,boolean hasOtp)throws Exception{
        return encryptWith(obj,trafficKey,kid,eventAad(deviceId,eventId,kind,occurredAt,subId,hasOtp));
    }

    public JSONObject decryptCommand(JSONObject envelope,String commandId,String type,long createdAt,long expiresAt,String idempotencyKey)throws Exception{
        int v=envelope.optInt("v");
        if(v==1){
            if(legacyMasterKey==null||legacyMasterKey.length!=32)throw new SecurityException("Legacy command not supported by independent-key node");
            return decryptWith(envelope,legacyMasterKey,AAD_COMMAND_V1);
        }
        if(v!=2 || !"A256GCM".equals(envelope.optString("alg"))) throw new SecurityException("Unsupported envelope");
        if(!kid.equals(envelope.optString("kid"))) throw new SecurityException("Key id mismatch");
        return decryptWith(envelope,trafficKey,commandAad(deviceId,commandId,type,createdAt,expiresAt,idempotencyKey));
    }

    public JSONObject encryptLocal(JSONObject obj)throws Exception{
        return encryptWith(obj,localKey,localKid,AAD_LOCAL);
    }

    public JSONObject decryptLocal(JSONObject envelope)throws Exception{
        int v=envelope.optInt("v");
        if(v==1){
            if(legacyMasterKey==null||legacyMasterKey.length!=32)throw new SecurityException("Legacy local key unavailable");
            return decryptWith(envelope,legacyMasterKey,AAD_EVENT_V1);
        }
        if(v!=2)throw new SecurityException("Unsupported local envelope");
        String envelopeKid=envelope.optString("kid");
        if(localKid.equals(envelopeKid))return decryptWith(envelope,localKey,AAD_LOCAL);
        if(kid.equals(envelopeKid))return decryptWith(envelope,trafficKey,"simhub-local-pending-sms-v1");
        throw new SecurityException("Local key mismatch");
    }

    private JSONObject encryptWith(JSONObject obj,byte[] key,String keyId,String aad)throws Exception{
        byte[] iv=new byte[12];random.nextBytes(iv);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct=c.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v",2).put("alg","A256GCM").put("kid",keyId).put("iv",b64(iv)).put("ct",b64(ct));
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

    public static byte[] decryptBootstrapNodeKey(JSONObject envelope,byte[] bootstrap,String expectedKid)throws Exception{
        if(bootstrap==null||bootstrap.length!=32)throw new SecurityException("Bootstrap key length invalid");
        if(envelope==null||!"A256GCM".equals(envelope.optString("alg")))throw new SecurityException("Bootstrap envelope invalid");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(bootstrap,"AES"),new GCMParameterSpec(128,ub64(envelope.getString("iv"))));
        c.updateAAD(("simhub-bootstrap-node-key-v1|"+expectedKid).getBytes(StandardCharsets.UTF_8));
        byte[] raw=c.doFinal(ub64(envelope.getString("ct")));
        if(raw.length!=32||!keyId(raw).equals(expectedKid)){Arrays.fill(raw,(byte)0);throw new SecurityException("Bootstrap Node Key mismatch");}
        return raw;
    }

    private static String field(String s){return b64((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    public static String eventAad(String deviceId,String eventId,String kind,long occurredAt,String subId,boolean hasOtp){
        return "simhub-event-v2|"+field(deviceId)+"|"+field(eventId)+"|"+field(kind)+"|"+occurredAt+"|"+field(subId)+"|"+(hasOtp?"1":"0");
    }
    public static String commandAad(String deviceId,String commandId,String type,long createdAt,long expiresAt,String idempotencyKey){
        return "simhub-command-v2|"+field(deviceId)+"|"+field(commandId)+"|"+field(type)+"|"+createdAt+"|"+expiresAt+"|"+field(idempotencyKey);
    }
    public static String b64(byte[] b){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static byte[] ub64(String s){return java.util.Base64.getUrlDecoder().decode(s);}
}
