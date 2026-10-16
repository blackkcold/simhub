package com.blackkcold.simhub;

import android.content.Context;
import com.blackkcold.simhub.p2p.PeerCrypto;
import org.json.JSONObject;

/** Preserve legacy pending SMS format; independent local wrapping for unenrolled peers. */
public final class PendingSmsCrypto {
    private static synchronized byte[] key(Context c)throws Exception{SecretStore s=new SecretStore(c);byte[] k=s.getBytes("p2p.pending-sms-local");if(k==null){k=PeerCrypto.random(32);s.putBytes("p2p.pending-sms-local",k);}return k;}
    public static JSONObject encrypt(Context c,JSONObject data)throws Exception{
        if(new AgentConfig(c).isEnrolled()&&!data.optString("commandId").startsWith("p2p-"))return new CryptoBox(c).encryptLocal(data);
        byte[] iv=PeerCrypto.random(12),k=key(c);
        try{return new JSONObject().put("domain","p2p-local-pending-v1").put("iv",PeerCrypto.b64(iv)).put("ct",PeerCrypto.b64(PeerCrypto.crypt(true,k,iv,"pending-sms-local",PeerCrypto.utf8(data.toString()))));}finally{java.util.Arrays.fill(k,(byte)0);}
    }
    public static JSONObject decrypt(Context c,JSONObject data)throws Exception{
        if(!"p2p-local-pending-v1".equals(data.optString("domain")))return new CryptoBox(c).decryptLocal(data);
        byte[] k=key(c);try{return new JSONObject(new String(PeerCrypto.crypt(false,k,PeerCrypto.unb64(data.getString("iv")),"pending-sms-local",PeerCrypto.unb64(data.getString("ct"))),java.nio.charset.StandardCharsets.UTF_8));}finally{java.util.Arrays.fill(k,(byte)0);}
    }
    private PendingSmsCrypto(){}
}
