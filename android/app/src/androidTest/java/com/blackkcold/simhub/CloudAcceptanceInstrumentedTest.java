package com.blackkcold.simhub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Cloud-only emulator + ephemeral HTTPS Relay; all identities and data are synthetic. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CloudAcceptanceInstrumentedTest {
    private static final String ORIGIN="https://127.0.0.1:18443";
    private static Context context;
    private static AgentConfig config;
    private static byte[] nodeKey;
    private static String deviceId,adminToken;

    @BeforeClass public static void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle args=InstrumentationRegistry.getArguments();
        deviceId=args.getString("ciDeviceId","");
        String deviceToken=args.getString("ciDeviceToken","");
        adminToken=args.getString("ciAdminToken","");
        assertFalse("Synthetic CI enrollment missing",deviceId.isBlank()||deviceToken.isBlank()||adminToken.isBlank());
        context.getSharedPreferences("simhub_location_privacy_v1",Context.MODE_PRIVATE).edit().clear().commit();
        nodeKey=new byte[32];for(int i=0;i<nodeKey.length;i++)nodeKey[i]=(byte)(i+1);
        config=new AgentConfig(context);
        config.setNodeEnrollment(ORIGIN,deviceId,"Cloud emulator",deviceToken,CryptoBox.keyId(nodeKey),nodeKey);
    }

    @AfterClass public static void cleanup(){
        if(context!=null){
            context.getSharedPreferences("simhub_location_privacy_v1",Context.MODE_PRIVATE).edit().clear().commit();
            LocalStore.get(context).resetForReenrollment();
        }
        if(config!=null)config.clearEnrollment();
        if(nodeKey!=null)Arrays.fill(nodeKey,(byte)0);
    }

    private JSONObject adminRequest(String path,JSONObject body)throws Exception{
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ORIGIN+path).openConnection();
        connection.setRequestProperty("Authorization","Bearer "+adminToken);
        connection.setConnectTimeout(8000);connection.setReadTimeout(15000);
        try{
            if(body!=null){
                connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream output=connection.getOutputStream()){
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status=connection.getResponseCode();
            assertTrue("Cloud Relay rejected administrator request: "+status,status==200||status==201);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1){
                    output.write(buffer,0,n);
                    if(output.size()>1024*1024)throw new IllegalStateException("Relay response too large");
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }
    private JSONObject adminGet(String path)throws Exception{return adminRequest(path,null);}

    private JSONObject runLocationCommand(String expectedState)throws Exception{
        String id="ci-location-"+UUID.randomUUID();
        long created=System.currentTimeMillis()/1000,expires=created+300;
        String type="device.location_once";
        JSONObject plain=new JSONObject().put("action",type).put("commandId",id).put("expiresAt",expires);
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(nodeKey,"AES"),new GCMParameterSpec(128,iv));
        aes.updateAAD(CryptoBox.commandAad(deviceId,id,type,created,expires,id).getBytes(StandardCharsets.UTF_8));
        JSONObject envelope=new JSONObject().put("v",2).put("alg","A256GCM").put("kid",CryptoBox.keyId(nodeKey))
            .put("iv",CryptoBox.b64(iv)).put("ct",CryptoBox.b64(aes.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8))));
        adminRequest("/api/v1/devices/"+deviceId+"/commands",new JSONObject().put("type",type)
            .put("commandId",id).put("idempotencyKey",id).put("createdAt",created).put("expiresAt",expires)
            .put("ciphertext",envelope));
        ApiClient api=new ApiClient(context);
        for(int attempt=0;attempt<20;attempt++){
            api.fetchCommands();api.flushCommandAcks();
            JSONArray commands=adminGet("/api/v1/commands/recent?limit=20").getJSONArray("commands");
            for(int i=0;i<commands.length();i++){
                JSONObject command=commands.getJSONObject(i);
                if(id.equals(command.optString("commandId"))&&expectedState.equals(command.optString("state")))return command;
            }
            Thread.sleep(250);
        }
        fail("Location command did not reach "+expectedState);
        return null;
    }

    @Test public void test01TelemetryReachesCloudRelay()throws Exception{
        JSONObject telemetry=null;
        for(int i=0;i<30;i++){
            telemetry=DeviceTelemetry.capture(context);
            if(telemetry.optInt("batteryPct",-1)==65)break;
            Thread.sleep(250);
        }
        assertNotNull(telemetry);
        assertEquals("Emulator battery sample did not propagate",65,telemetry.optInt("batteryPct",-1));
        assertTrue(telemetry.optLong("sampledAt",0)>0);
        if(telemetry.has("wifiSignalLevel"))assertTrue(telemetry.getInt("wifiSignalLevel")>=0&&telemetry.getInt("wifiSignalLevel")<=4);
        JSONObject posted=new ApiClient(context).putState("cloud-telemetry");
        JSONObject state=adminGet("/api/v1/devices/"+deviceId+"/state").getJSONObject("state");
        assertEquals(65,state.getInt("batteryPct"));
        assertTrue(state.optLong("sampledAt",0)>0);
        assertEquals("cloud-telemetry",state.getString("manualRequestId"));
        if(posted.has("wifiConnected"))assertEquals(posted.getBoolean("wifiConnected"),state.getBoolean("wifiConnected"));
        if(posted.has("wifiSignalLevel"))assertEquals(posted.getInt("wifiSignalLevel"),state.getInt("wifiSignalLevel"));
        assertNotNull(state.optJSONArray("subscriptions"));
        assertFalse(state.has("latitude"));assertFalse(state.has("longitude"));
    }

    @Test public void test02LocationConsentEncryptionAndRevocation()throws Exception{
        assertFalse(NodeLocationManager.enabled(context));
        NodeLocationManager.setRemoteAllowed(context,true);
        assertFalse(NodeLocationManager.remoteAllowed(context));
        try{NodeLocationManager.captureAndQueue(context);fail("Location enabled without consent");}
        catch(SecurityException expected){}
        assertEquals("location_sharing_disabled",runLocationCommand("failed").getJSONObject("result").getString("reason"));
        NodeLocationManager.setEnabled(context,true);
        assertFalse(NodeLocationManager.remoteAllowed(context));
        assertEquals("remote_location_not_authorized",runLocationCommand("rejected").getJSONObject("result").getString("reason"));
        HubActivity.visibleForLocation=false;
        try{NodeLocationManager.captureAndQueue(context,true);fail("Background location accepted");}
        catch(SecurityException expected){}
        NodeLocationManager.setRemoteAllowed(context,true);
        assertTrue(NodeLocationManager.remoteAllowed(context));

        Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context,HubActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Node activity must be foreground",HubActivity.visibleForLocation);
            JSONObject result=runLocationCommand("succeeded").getJSONObject("result");
            assertTrue("Encrypted location was not acknowledged",result.optBoolean("uploaded"));
            JSONObject location=adminGet("/api/v1/devices/"+deviceId+"/location").getJSONObject("location");
            assertFalse(location.has("latitude"));assertFalse(location.has("longitude"));
            JSONObject cipher=location.getJSONObject("ciphertext");
            assertEquals("A256GCM",cipher.getString("alg"));
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(nodeKey,"AES"),new GCMParameterSpec(128,CryptoBox.ub64(cipher.getString("iv"))));
            String aad=CryptoBox.eventAad(deviceId,location.getString("eventId"),"device.location",
                location.getLong("occurredAt"),location.getString("subscriptionId"),location.getBoolean("hasOtp"));
            aes.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            JSONObject plain=new JSONObject(new String(aes.doFinal(CryptoBox.ub64(cipher.getString("ct"))),StandardCharsets.UTF_8));
            assertTrue(Math.abs(plain.getDouble("latitude"))<=90);
            assertTrue(Math.abs(plain.getDouble("longitude"))<=180);
            assertTrue(plain.getLong("sampledAt")>0);
            String oldEpoch=context.getSharedPreferences("simhub_location_privacy_v1",Context.MODE_PRIVATE).getString("epoch","");
            NodeLocationManager.setEnabled(context,false);
            assertFalse(NodeLocationManager.remoteAllowed(context));
            ApiClient api=new ApiClient(context);NodeLocationManager.purgeIfNeeded(context,api);
            assertTrue(adminGet("/api/v1/devices/"+deviceId+"/location").isNull("location"));
            location.put("metadata",new JSONObject().put("locationEpoch",oldEpoch));
            JSONObject replay=api.request("POST","/api/v1/devices/"+deviceId+"/events",location);
            assertTrue("Revoked epoch was replayed",replay.optBoolean("suppressed"));
        }finally{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }
}
