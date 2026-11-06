package com.blackkcold.simhub;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.telephony.SubscriptionManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Runs on min-SDK emulators with synthetic data, never sends a radio SMS. */
@RunWith(AndroidJUnit4.class)
public class NodeRecoveryInstrumentedTest {
    private Context isolatedContext(){
        Context real=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="node-recovery-"+UUID.randomUUID()+"-";
        return new ContextWrapper(real){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                return super.getSharedPreferences(prefix+name,mode);
            }
        };
    }
    @Test public void smsManagerFactorySupportsMinimumSdk(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(SmsSender.managerForSubscription(c,SubscriptionManager.INVALID_SUBSCRIPTION_ID));
    }
    @Test public void localSmsEnvelopeSurvivesUnpairedAndResetState()throws Exception{
        Context c=isolatedContext();
        AgentConfig config=new AgentConfig(c);
        assertFalse(config.isEnrolled());
        JSONObject envelope=PendingSmsCrypto.encrypt(c,new JSONObject().put("body","synthetic pending SMS"));
        config.clearEnrollment();
        assertEquals("synthetic pending SMS",PendingSmsCrypto.decrypt(c,envelope).getString("body"));
    }
    private AgentConfig rotationPending(Context c)throws Exception{
        AgentConfig config=new AgentConfig(c);
        byte[] key=new byte[32];key[0]=19;
        config.setNodeEnrollment("https://relay.example.invalid","synthetic-device","synthetic","old-token",CryptoBox.keyId(key),key);
        config.stageDeviceToken("candidate-token",1); // Expired locally; server may already have committed.
        return config;
    }
    @Test public void committedCandidateIsRecoveredAfterLostResponseAndLocalExpiry()throws Exception{
        Context c=isolatedContext();AgentConfig config=rotationPending(c);
        List<String> calls=new ArrayList<>();
        ApiClient client=new ApiClient(c,(method,path,body,token)->{
            calls.add(method+" "+path+" "+token);
            assertTrue(path.endsWith("/token/commit"));
            assertEquals("candidate-token",token);
            return new JSONObject().put("tokenIssuedAt",1234);
        });
        assertFalse(client.recoverPendingDeviceToken());
        assertFalse(client.recoverPendingDeviceToken());
        assertEquals(1,calls.size());
        assertEquals("candidate-token",config.deviceToken());
        assertEquals(1234,config.tokenIssuedAt());
        assertFalse(config.tokenRotationPending());
    }
    @Test public void rejectedCandidateRequiresVerifiedPreviousCredential()throws Exception{
        Context c=isolatedContext();AgentConfig config=rotationPending(c);
        List<String> calls=new ArrayList<>();
        ApiClient client=new ApiClient(c,(method,path,body,token)->{
            calls.add(token);
            if("candidate-token".equals(token))throw new ApiClient.ApiFailure(401,"unauthorized","synthetic rejection",0);
            assertTrue(path.endsWith("/lifecycle"));
            return new JSONObject().put("resetRequired",false);
        });
        assertFalse(client.recoverPendingDeviceToken());
        assertEquals(java.util.Arrays.asList("candidate-token","candidate-token","old-token"),calls);
        assertEquals("old-token",config.deviceToken());
        assertFalse(config.tokenRotationPending());
    }
    @Test public void ambiguousTokenRecoveryRetainsCandidate()throws Exception{
        Context c=isolatedContext();AgentConfig config=rotationPending(c);
        ApiClient client=new ApiClient(c,(method,path,body,token)->{
            if(path.endsWith("/token/commit"))throw new ApiClient.ApiFailure(401,"unauthorized","synthetic rejection",0);
            throw new java.io.IOException("synthetic network failure");
        });
        try{client.recoverPendingDeviceToken();fail("Expected retryable failure");}
        catch(java.io.IOException expected){}
        assertTrue(config.tokenRotationPending());
        assertEquals("candidate-token",config.pendingDeviceToken());
        assertEquals("old-token",config.deviceToken());
    }
    @Test public void locationConsentCannotCrossEnrollmentEvenForSameDeviceId()throws Exception{
        Context c=isolatedContext();AgentConfig config=rotationPending(c);
        NodeLocationManager.setEnabled(c,true);
        NodeLocationManager.setRemoteAllowed(c,true);
        assertTrue(NodeLocationManager.enabled(c));
        assertTrue(NodeLocationManager.remoteAllowed(c));
        String firstGeneration=config.enrollmentGeneration();
        byte[] key=new byte[32];key[0]=19;
        config.setNodeEnrollment("https://relay.example.invalid","synthetic-device","synthetic","different-token",CryptoBox.keyId(key),key);
        assertNotEquals(firstGeneration,config.enrollmentGeneration());
        assertFalse(NodeLocationManager.enabled(c));
        assertFalse(NodeLocationManager.remoteAllowed(c));
        assertFalse(c.getSharedPreferences("simhub_location_privacy_v1",Context.MODE_PRIVATE).contains("registeredEpoch"));
    }
    @Test public void suppressedReceiptDeduplicatesWithoutReportingPublished(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalStore store=LocalStore.get(c);String id="suppressed-test-"+UUID.randomUUID();
        try{
            store.markEventAccepted(id,"suppressed");
            assertTrue(store.hasSeenEvent(id));
            assertFalse(store.hasUploadedReceipt(id));
            assertEquals("suppressed",store.eventReceiptDisposition(id));
        }finally{store.getWritableDatabase().delete("uploaded_event_ids","id=?",new String[]{id});}
    }
    @Test public void existingReceiptSchemaUpgradesWithoutLosingAcknowledgements(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null);
        try{
            db.execSQL("CREATE TABLE uploaded_event_ids(id TEXT PRIMARY KEY,uploaded_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO uploaded_event_ids VALUES('old-receipt',123)");
            LocalStore.get(c).onUpgrade(db,5,6);
            try(android.database.Cursor row=db.rawQuery("SELECT disposition FROM uploaded_event_ids WHERE id='old-receipt'",null)){
                assertTrue(row.moveToFirst());assertEquals("stored",row.getString(0));
            }
        }finally{db.close();}
    }
}
