package com.blackkcold.simhub;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.net.URI;
import java.util.Arrays;

public final class EnrollmentManager {
    public static void enroll(Context c,String link)throws Exception{
        AgentConfig existing=new AgentConfig(c);if(existing.isEnrolled()||existing.resetPending())throw new IllegalStateException("This node is already enrolled. Reset enrollment before pairing it to another vault or relay.");
        AppLogger.i(c,"Enrollment","Validating enrollment package");
        Uri u=Uri.parse(link);if(!"simhub".equalsIgnoreCase(u.getScheme())||!"enroll".equalsIgnoreCase(u.getHost()))throw new IllegalArgumentException("Invalid enrollment link");
        String server=u.getQueryParameter("server"),token=u.getQueryParameter("token"),name=u.getQueryParameter("name"),versionText=u.getQueryParameter("v");if(server==null||token==null)throw new IllegalArgumentException("Enrollment link missing fields");
        URI su=URI.create(server);if(!"https".equalsIgnoreCase(su.getScheme())||su.getHost()==null)throw new SecurityException("Relay must use HTTPS");if(name==null||name.isBlank())name="Android SIM Node";
        int version=0;try{version=Integer.parseInt(versionText==null?"0":versionText);}catch(Exception ignored){}
        byte[] key=null,bootstrap=null;if(version>=4){String bootstrapText=u.getQueryParameter("bootstrap");if(bootstrapText==null)throw new SecurityException("Enrollment bootstrap secret missing");bootstrap=CryptoBox.ub64(bootstrapText);if(bootstrap.length!=32){Arrays.fill(bootstrap,(byte)0);throw new SecurityException("Enrollment bootstrap secret length invalid");}}
        String bootstrapProof=version>=4?CryptoBox.bootstrapProof(bootstrap):null;JSONObject r=ApiClient.enroll(server.replaceAll("/+$",""),token,name,bootstrapProof);
        try{
            if(version>=4){String kid=r.optString("keyId","");JSONObject envelope=r.optJSONObject("bootstrapEnvelope");if(kid.isBlank()||envelope==null)throw new SecurityException("Relay bootstrap envelope missing");key=CryptoBox.decryptBootstrapNodeKey(envelope,bootstrap,kid);existing.setNodeEnrollment(server.replaceAll("/+$",""),r.getString("deviceId"),name,r.getString("deviceToken"),kid,key);}
            else{String keyText=u.getQueryParameter("key"),keyId=u.getQueryParameter("keyId");if(keyText==null)throw new SecurityException("Legacy enrollment key missing");key=CryptoBox.ub64(keyText);if(key.length!=32)throw new SecurityException("Enrollment key length invalid");if(version>=3&&keyId!=null&&!keyId.isBlank()){String computed=CryptoBox.keyId(key);if(!computed.equals(keyId))throw new SecurityException("Node key id mismatch");existing.setNodeEnrollment(server.replaceAll("/+$",""),r.getString("deviceId"),name,r.getString("deviceToken"),keyId,key);}else existing.setLegacyEnrollment(server.replaceAll("/+$",""),r.getString("deviceId"),name,r.getString("deviceToken"),key);}
        }finally{if(key!=null)Arrays.fill(key,(byte)0);if(bootstrap!=null)Arrays.fill(bootstrap,(byte)0);}
        SyncJobService.schedule(c);SyncJobService.scheduleNow(c);AppLogger.i(c,"Enrollment","Node enrollment stored successfully version="+version);
    }
    public static void requestReset(Context c){
        AgentConfig cfg=new AgentConfig(c);
        if(!cfg.isEnrolled()&&!cfg.resetPending()){reset(c);return;}
        cfg.markResetPending();
        RelayForegroundService.stop(c);
        SyncJobService.scheduleNow(c);
        AppLogger.i(c,"Enrollment","Reset requested; awaiting relay confirmation");
    }
    /** Explicit user-confirmed escape hatch when Relay cannot be reached or credentials are lost. */
    public static void forceLocalReset(Context c){
        AgentConfig cfg=new AgentConfig(c);
        if(!cfg.resetPending() && !cfg.resetRecoveryRequired())
            throw new IllegalStateException("Force local reset is only available for a pending reset");
        reset(c);
        AppLogger.w(c,"Enrollment","User confirmed local-only reset; server may retain old node");
    }
    public static void retryReset(Context c){
        AgentConfig cfg=new AgentConfig(c);
        if(!cfg.resetPending())return;
        cfg.retryPendingReset();
        SyncJobService.scheduleNow(c);
    }
    public static void reset(Context c){
        RelayForegroundService.stop(c);
        SharedPoolClient.clearLocalForReset(c);
        SimTagStore.clearAll(c);
        LocalStore.get(c).resetForReenrollment();
        new AgentConfig(c).clearEnrollment();
        AppLogger.i(c,"Enrollment","Node enrollment and pool secrets cleared");
    }
    private EnrollmentManager(){}
}