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
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath(prefix+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){
                return super.openOrCreateDatabase(prefix+name,mode,factory);
            }
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){
                return super.openOrCreateDatabase(prefix+name,mode,factory,handler);
            }
        };
    }
    @Test public void smsManagerFactorySupportsMinimumSdk(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(SmsSender.managerForSubscription(c,SubscriptionManager.INVALID_SUBSCRIPTION_ID));
    }
    @Test public void incomingSmsNotificationHasNoMessageOrSenderData(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.Notification notification=NotificationHelper.incomingSmsNotification(c);
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility);
        assertEquals(android.app.Notification.CATEGORY_MESSAGE,notification.category);
        assertEquals(c.getString(R.string.sms_notification_title),notification.extras.getString(android.app.Notification.EXTRA_TITLE));
        assertEquals(c.getString(R.string.sms_notification_body),notification.extras.getString(android.app.Notification.EXTRA_TEXT));
        assertFalse(notification.extras.containsKey(android.app.Notification.EXTRA_MESSAGES));
        assertFalse(notification.extras.containsKey(android.app.Notification.EXTRA_PEOPLE_LIST));
        assertNotNull(notification.contentIntent);
        assertEquals(c.getPackageName(),notification.contentIntent.getCreatorPackage());
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
    @Test public void alreadyUploadedSmsCanEnterNewlyEnabledSharedPoolOnce()throws Exception{
        Context c=isolatedContext();rotationPending(c);
        SharedPoolClient pool=new SharedPoolClient(c);
        c.getSharedPreferences("simhub_pool_v1",Context.MODE_PRIVATE).edit()
            .putBoolean("requested",true).putBoolean("approved",true).commit();
        LocalStore local=LocalStore.get(c);String id="shared-test-"+UUID.randomUUID();
        local.markEventSent(id);
        int ordinaryBefore=local.pendingEventCount();
        JSONObject payload=new JSONObject().put("direction","in").put("body","synthetic shared history");
        try{
            assertTrue(SharedPoolClient.needsStage(c,id));
            assertTrue(EventQueue.queue(c,id,"sms.history",1234,-1,false,payload,new JSONObject()));
            assertFalse(SharedPoolClient.needsStage(c,id));
            assertEquals(1,pool.pendingUploadCount());
            assertEquals(ordinaryBefore,local.pendingEventCount());
            assertTrue(EventQueue.queue(c,id,"sms.history",1234,-1,false,payload,new JSONObject()));
            assertEquals(1,pool.pendingUploadCount());
            try(android.database.sqlite.SQLiteDatabase db=c.openOrCreateDatabase("simhub-pool-cache.db",0,null);
                android.database.Cursor row=db.rawQuery("SELECT local_cipher FROM staged WHERE id=?",new String[]{id})){
                assertTrue(row.moveToFirst());assertFalse(row.getString(0).contains("synthetic shared history"));
            }
        }finally{
            local.getWritableDatabase().delete("uploaded_event_ids","id=?",new String[]{id});
            SharedPoolClient.clearLocalForReset(c);
        }
    }
    @Test public void sharedBackfillCheckpointSurvivesRecreation()throws Exception{
        Context c=isolatedContext();rotationPending(c);
        SharedPoolClient first=new SharedPoolClient(c);
        c.getSharedPreferences("simhub_pool_v1",Context.MODE_PRIVATE).edit().putBoolean("requested",true).putBoolean("approved",true).commit();
        first.historyRowStaged(first.privacyScope(),1000,7,true);
        SharedPoolClient resumed=new SharedPoolClient(c);
        assertEquals(1000,resumed.historyDate());assertEquals(7,resumed.historyId());
        assertEquals(99,resumed.initialHistoryRemaining());assertFalse(resumed.initialHistoryStaged());
        assertEquals(0,new AgentConfig(c).historyDate());
        SharedPoolClient.clearLocalForReset(c);
    }
    @Test public void oldSharedWorkCannotRepopulateAfterOptOutOrResetAndReenable()throws Exception{
        for(boolean reset:new boolean[]{false,true}){
            Context c=isolatedContext();rotationPending(c);
            SharedPoolClient pool=new SharedPoolClient(c);
            SharedPreferences prefs=c.getSharedPreferences("simhub_pool_v1",Context.MODE_PRIVATE);
            prefs.edit().putBoolean("requested",true).putBoolean("approved",true).putString("key_epochs","1").commit();
            byte[] key=new byte[32];key[0]=29;new SecretStore(c).putBytes("pool-key-1",key);
            byte[] iv=new byte[12];iv[0]=12;
            javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,iv));
            cipher.updateAAD("simhub-pool-sms-v1|default|other-node|event-1|100|sim-1|1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject encrypted=new JSONObject().put("alg","A256GCM").put("iv",CryptoBox.b64(iv))
                .put("ct",CryptoBox.b64(cipher.doFinal("{\"body\":\"synthetic shared cache\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            JSONObject row=new JSONObject().put("seq",1).put("deviceId","other-node").put("originEventId","event-1")
                .put("occurredAt",100).put("channelId","sim-1").put("epoch",1).put("ciphertext",encrypted);
            JSONObject response=new JSONObject().put("messages",new org.json.JSONArray().put(row));
            String previous=pool.privacyScope();
            assertEquals(1,pool.acceptDownloaded(previous,response,0,false));
            assertEquals(1,pool.cached(10).size());
            if(reset)SharedPoolClient.clearLocalForReset(c);else pool.disableLocalForPrivacy();
            assertFalse(pool.optedIn());assertTrue(pool.cached(10).isEmpty());
            assertNull(new SecretStore(c).getBytes("pool-key-1"));
            // Even reauthorizing with the same pool key cannot validate work from
            // before the consent change; the local generation must also match.
            prefs.edit().putBoolean("requested",true).putBoolean("approved",true).putString("key_epochs","1").commit();
            new SecretStore(c).putBytes("pool-key-1",key);
            assertNotEquals(previous,pool.privacyScope());
            try{pool.acceptDownloaded(previous,response,0,false);fail("Stale download accepted");}catch(java.io.IOException expected){}
            try{pool.stageInScope(previous,"old-event",100,"sim-1",new JSONObject().put("body","stale"));fail("Stale stage accepted");}catch(java.io.IOException expected){}
            try{pool.historyRowStaged(previous,1000,7,true);fail("Stale cursor accepted");}catch(java.io.IOException expected){}
            assertEquals(0,pool.pendingUploadCount());assertEquals(0,pool.historyDate());assertTrue(pool.cached(10).isEmpty());
            SharedPoolClient.clearLocalForReset(c);
        }
    }
    @Test public void interruptedOtaWorkersBecomeRetryableTerminalFailures()throws Exception{
        for(String stage:new String[]{"checking","downloading","verified"}){
            Context c=isolatedContext();String id="ota-recovery-"+UUID.randomUUID();
            LocalStore local=LocalStore.get(c);assertTrue(local.claimCommand(id,"ota.install"));
            SharedPreferences state=c.getSharedPreferences("simhub_remote_ota_v1",Context.MODE_PRIVATE);
            state.edit().putString("command",id).putString("stage",stage).putString("process","dead-process")
                .putString("operation","interrupted-operation").putInt("targetCode",stage.equals("checking")?0:Integer.MAX_VALUE)
                .putLong("updatedAt",1).commit();
            try{
                RemoteOta.reconcileInstalledVersion(c);
                assertEquals("failed",RemoteOta.currentStage(c));
                assertFalse(RemoteOta.active(c));
                assertEquals("update_interrupted_before_install",state.getString("error",""));
                assertEquals("failed",local.commandState(id));
            }finally{
                local.getWritableDatabase().delete("processed_commands","id=?",new String[]{id});
                local.getWritableDatabase().delete("command_acks","command_id=?",new String[]{id});
            }
        }
    }
    @Test public void cancellingOrphanedOtaDoesNotWaitForWorkerAndLateCallbackCannotClobberNext(){
        Context c=isolatedContext();SharedPreferences state=c.getSharedPreferences("simhub_remote_ota_v1",Context.MODE_PRIVATE);
        state.edit().putString("command","synthetic-ota-cancel").putString("operation","old-operation")
            .putString("process","dead-process").putString("stage","checking").commit();
        assertTrue(RemoteOta.cancel(c));
        assertEquals("failed",RemoteOta.currentStage(c));assertFalse(RemoteOta.active(c));
        assertEquals("update_cancelled",state.getString("error",""));
        state.edit().putString("command","synthetic-new-ota").putString("operation","new-operation")
            .putString("stage","checking").putBoolean("cancelRequested",false).commit();
        RemoteOta.fail(c,"old-operation","late-failure");
        assertEquals("checking",RemoteOta.currentStage(c));
        try{RemoteOta.stage(c,"old-operation","installing");fail("Old worker changed replacement operation");}
        catch(IllegalStateException expected){}
    }
}
