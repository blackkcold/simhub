package com.blackkcold.simhub.p2p;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** P2P domain only. No Relay credentials, Node Key or Vault key is accepted. */
public final class PeerCrypto {
    public static final String DOMAIN="simhub-peer-v1";
    private static final SecureRandom RANDOM=new SecureRandom();
    public static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static byte[] unb64(String s){if(s.length()>800_000)throw new SecurityException("oversize_encoding");return Base64.getUrlDecoder().decode(s);}
    public static byte[] random(int n){byte[] b=new byte[n];RANDOM.nextBytes(b);return b;}
    public static String digest(byte[] b)throws GeneralSecurityException{return b64(MessageDigest.getInstance("SHA-256").digest(b));}
    public static KeyPair ephemeral()throws GeneralSecurityException{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    public static PublicKey publicKey(String s)throws GeneralSecurityException{
        byte[] der=unb64(s);if(der.length>256)throw new SecurityException("bad_peer_key");
        PublicKey k=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if(!(k instanceof java.security.interfaces.ECPublicKey ec))throw new SecurityException("bad_peer_curve");
        AlgorithmParameters params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec expected=params.getParameterSpec(ECParameterSpec.class),actual=ec.getParams();
        if(!actual.getCurve().equals(expected.getCurve())||!actual.getGenerator().equals(expected.getGenerator())||!actual.getOrder().equals(expected.getOrder())||actual.getCofactor()!=expected.getCofactor())throw new SecurityException("bad_peer_curve");
        return k;
    }
    public static byte[] dh(PrivateKey key,PublicKey peer)throws GeneralSecurityException{KeyAgreement a=KeyAgreement.getInstance("ECDH");a.init(key);a.doPhase(peer,true);return a.generateSecret();}
    public static String sign(PrivateKey key,String text)throws GeneralSecurityException{Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(key);s.update(utf8(DOMAIN+"|"+text));return b64(s.sign());}
    public static void verify(String key,String text,String signature)throws GeneralSecurityException{Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(publicKey(key));s.update(utf8(DOMAIN+"|"+text));if(!s.verify(unb64(signature)))throw new SecurityException("peer_signature_rejected");}
    public static byte[] derive(byte[] secret,byte[] salt,String info)throws GeneralSecurityException{
        Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(salt,"HmacSHA256"));byte[] prk=m.doFinal(secret);
        try{m.init(new SecretKeySpec(prk,"HmacSHA256"));m.update(utf8(DOMAIN+"|"+info));m.update((byte)1);return m.doFinal();}finally{Arrays.fill(prk,(byte)0);}
    }
    public static byte[] crypt(boolean encrypt,byte[] key,byte[] nonce,String aad,byte[] data)throws GeneralSecurityException{
        if(key.length!=32||nonce.length!=12)throw new SecurityException("bad_peer_cipher_parameters");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(utf8(DOMAIN+"|"+aad));return c.doFinal(data);
    }
    public static String pairTranscript(String sid,long expires,String node,String nodeEphemeral,String controller,String controllerEphemeral,String permissions){
        return "pair|"+sid+"|"+expires+"|"+node+"|"+nodeEphemeral+"|"+controller+"|"+controllerEphemeral+"|"+permissions;
    }
    private PeerCrypto(){}
}
