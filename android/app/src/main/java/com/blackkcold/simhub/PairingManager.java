package com.blackkcold.simhub;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Device-initiated, short-lived pairing: Relay never sees a plaintext Node Key. */
public final class PairingManager {
    public static final class Session {
        public final String server,id,pollToken,code,fingerprint;
        final KeyPair keyPair;
        Session(String server,String id,String pollToken,String code,String fingerprint,KeyPair keyPair){
            this.server=server;this.id=id;this.pollToken=pollToken;this.code=code;
            this.fingerprint=fingerprint;this.keyPair=keyPair;
        }
    }
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] ub64(String value){return Base64.decode(value,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    private static String b64(byte[] value){return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    private static byte[] sha256(byte[] data)throws Exception{return MessageDigest.getInstance("SHA-256").digest(data);}
    private static String hex(byte[] input){
        StringBuilder s=new StringBuilder();for(byte x:input)s.append(String.format(java.util.Locale.US,"%02x",x&255));return s.toString();
    }
    private static String requireServer(String url){
        URI uri=URI.create(url.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
            throw new SecurityException("HTTPS Relay origin required");
        if(uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!"/".equals(uri.getRawPath()))
            throw new SecurityException("Relay URL must not contain a path");
        return uri.toString().replaceAll("/+$","");
    }
    public static Session start(Context c,String server)throws Exception{
        AgentConfig cfg=new AgentConfig(c);
        if(cfg.isEnrolled()||cfg.resetPending())throw new IllegalStateException("Unpair this device before creating a new pairing");
        String target=requireServer(server);
        KeyPairGenerator kpg=KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"),new SecureRandom());
        KeyPair kp=kpg.generateKeyPair();
        String publicKey=b64(kp.getPublic().getEncoded());
        JSONObject body=new JSONObject().put("publicKey",publicKey).put("name","Android SIM Node")
          .put("model",Build.MANUFACTURER+" "+Build.MODEL).put("osVersion",Build.VERSION.RELEASE)
          .put("appVersion",BuildConfig.VERSION_NAME);
        JSONObject result=ApiClient.pairingRequest(target,"POST","/api/v1/pairings/start",body,null);
        String fp=hex(sha256(utf8(publicKey))).substring(0,12).toUpperCase(java.util.Locale.US);
        return new Session(target,result.getString("requestId"),result.getString("pollToken"),result.getString("code"),fp,kp);
    }
    public static boolean poll(Context c,Session session)throws Exception{
        if(new AgentConfig(c).isEnrolled())return true;
        JSONObject state=ApiClient.pairingRequest(session.server,"GET","/api/v1/pairings/"+session.id+"/status",null,session.pollToken);
        if(!"approved".equals(state.optString("status")))return false;
        JSONObject envelope=state.getJSONObject("envelope");
        String keyId=state.getString("keyId"),deviceId=state.getString("deviceId");
        byte[] nodeKey=null,derived=null,shared=null;
        try{
            byte[] peerDer=ub64(envelope.getString("publicKey"));
            java.security.PublicKey remote=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerDer));
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");
            agreement.init(session.keyPair.getPrivate());
            agreement.doPhase(remote,true);
            shared=agreement.generateSecret();
            // HKDF-SHA256, matching WebCrypto HKDF derivation.
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(utf8("simhub-pair-v1|"+session.id),"HmacSHA256"));
            byte[] prk=mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk,"HmacSHA256"));
            mac.update(utf8("node-key"));mac.update((byte)1);
            byte[] block=mac.doFinal();
            derived=Arrays.copyOf(block,32);
            Arrays.fill(prk,(byte)0);Arrays.fill(block,(byte)0);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,ub64(envelope.getString("iv"))));
            cipher.updateAAD(utf8("simhub-pair-v1|"+session.id+"|"+keyId));
            JSONObject payload=new JSONObject(new String(cipher.doFinal(ub64(envelope.getString("ct"))),StandardCharsets.UTF_8));
            nodeKey=ub64(payload.getString("nodeKey"));
            if(nodeKey.length!=32||!keyId.equals(CryptoBox.keyId(nodeKey)))throw new SecurityException("Node key fingerprint mismatch");
            String deviceToken=payload.getString("deviceToken");
            if(deviceToken.length()<40)throw new SecurityException("Pairing device token malformed");
            String proof=hex(sha256(utf8("simhub-pair-complete-v1|"+session.id+"|"+deviceToken)));
            AgentConfig cfg=new AgentConfig(c);
            // Persist credentials and the pending completion proof BEFORE activation.
            cfg.setNodeEnrollment(session.server,deviceId,"Android SIM Node",deviceToken,keyId,nodeKey);
            cfg.stagePairCompletion(session.id,session.pollToken,proof);
            completePending(c);
            SyncJobService.schedule(c);SyncJobService.scheduleNow(c);
            return true;
        }finally{
            if(nodeKey!=null)Arrays.fill(nodeKey,(byte)0);
            if(derived!=null)Arrays.fill(derived,(byte)0);
            if(shared!=null)Arrays.fill(shared,(byte)0);
        }
    }
    /** Idempotent remote activation is retried on future sync cycles after network loss. */
    public static void completePending(Context c)throws Exception{
        AgentConfig cfg=new AgentConfig(c);
        String id=cfg.pendingPairId();
        if(id.isBlank())return;
        String bearer=cfg.pendingPairToken(),proof=cfg.pendingPairProof();
        if(bearer==null||proof==null)throw new SecurityException("Incomplete local pairing journal");
        ApiClient.pairingRequest(cfg.server(),"POST","/api/v1/pairings/"+id+"/complete",new JSONObject().put("proof",proof),bearer);
        cfg.clearPairCompletion();
    }
    private PairingManager(){}
}
