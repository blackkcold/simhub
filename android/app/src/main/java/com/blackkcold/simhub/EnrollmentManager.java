package com.blackkcold.simhub;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.net.URI;

public final class EnrollmentManager {
    public static void enroll(Context c,String link)throws Exception{
        AgentConfig existing=new AgentConfig(c);
        if(existing.isEnrolled())throw new IllegalStateException("This node is already enrolled. Reset enrollment before pairing it to another vault or relay.");
        Uri u=Uri.parse(link);
        if(!"simhub".equalsIgnoreCase(u.getScheme())||!"enroll".equalsIgnoreCase(u.getHost()))throw new IllegalArgumentException("Invalid enrollment link");
        String server=u.getQueryParameter("server"),token=u.getQueryParameter("token"),keyText=u.getQueryParameter("key"),keyId=u.getQueryParameter("keyId"),name=u.getQueryParameter("name"),version=u.getQueryParameter("v");
        if(server==null||token==null||keyText==null)throw new IllegalArgumentException("Enrollment link missing fields");
        URI su=URI.create(server);if(!"https".equalsIgnoreCase(su.getScheme())||su.getHost()==null)throw new SecurityException("Relay must use HTTPS");
        byte[] key=CryptoBox.ub64(keyText);if(key.length!=32)throw new SecurityException("Enrollment key length invalid");if(name==null||name.isBlank())name="Android SIM Node";
        JSONObject r=ApiClient.enroll(server.replaceAll("/+$",""),token,name);
        boolean independent=keyId!=null&&!keyId.isBlank()&&version!=null&&Integer.parseInt(version)>=3;
        if(independent){
            String computed=CryptoBox.keyId(key);if(!computed.equals(keyId))throw new SecurityException("Node key id mismatch");
            existing.setNodeEnrollment(server.replaceAll("/+$",""),r.getString("deviceId"),name,r.getString("deviceToken"),keyId,key);
        }else{
            existing.setLegacyEnrollment(server.replaceAll("/+$",""),r.getString("deviceId"),name,r.getString("deviceToken"),key);
        }
        SyncJobService.schedule(c);SyncJobService.scheduleNow(c);
    }
    public static void reset(Context c){
        RelayForegroundService.stop(c);
        LocalStore.get(c).resetForReenrollment();
        new AgentConfig(c).clearEnrollment();
    }
    private EnrollmentManager(){}
}
