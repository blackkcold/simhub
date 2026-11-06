package com.blackkcold.simhub.p2p;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TestName;
import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import android.provider.Telephony;
import org.json.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs production Keystore, SQLite, double-scan parser, server and provider on Android. */
@SuppressWarnings("deprecation")
public final class PeerInstrumentedTest {
    @Rule public TestName testName=new TestName();
    private PeerStore store;
    private PeerNodeServer server;
    private String controllerId;
    private byte[] root;
    @Before public void setUp()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();store=PeerStore.get(c);server=new PeerNodeServer(store);new Thread(server::run).start();
        KeyPair identity=PeerCrypto.ephemeral(),ephemeral=PeerCrypto.ephemeral();String pub=PeerCrypto.b64(identity.getPublic().getEncoded()),epub=PeerCrypto.b64(ephemeral.getPublic().getEncoded());controllerId=PeerCrypto.digest(identity.getPublic().getEncoded());
        PeerPairingManager manager=new PeerPairingManager(c);String offerQr=manager.offer("Sandbox Node","testSmsReceiptSurvivesRouteSwitchAndExpiry".equals(testName.getMethodName()),new JSONArray());
        JSONObject qr=new JSONObject(new String(PeerCrypto.unb64(offerQr.substring(12)),java.nio.charset.StandardCharsets.UTF_8));String offerBody=qr.getString("body");
        JSONObject offer=new JSONObject(new String(PeerCrypto.unb64(offerBody),java.nio.charset.StandardCharsets.UTF_8));PeerCrypto.verify(offer.getString("publicKey"),offerBody,qr.getString("signature"));
        JSONObject response=new JSONObject().put("v",offer.getInt("v")).put("kind","response").put("reversePermissions","").put("endpoints",new JSONArray()).put("offer",offerBody).put("offerHash",PeerCrypto.digest(PeerCrypto.utf8(offerBody))).put("publicKey",pub).put("ephemeral",epub).put("name","Sandbox Controller");
        String reply=PeerCrypto.b64(PeerCrypto.utf8(response.toString()));String replyQr="simhub-peer:"+PeerCrypto.b64(PeerCrypto.utf8(new JSONObject().put("body",reply).put("signature",PeerCrypto.sign(identity.getPrivate(),reply)).toString()));
        String transcript=PeerCrypto.pairTranscript(offer.getString("sid"),offer.getLong("expires"),offer.getString("publicKey"),offer.getString("ephemeral"),pub,epub,offer.getString("permissions")+(offer.optInt("v")==2?"|":""));
        root=PeerCrypto.derive(PeerCrypto.dh(ephemeral.getPrivate(),PeerCrypto.publicKey(offer.getString("ephemeral"))),PeerCrypto.utf8(PeerCrypto.digest(PeerCrypto.utf8(transcript))),"content-root");
        assertEquals(controllerId,manager.accept(replyQr));assertTrue(Arrays.equals(root,store.key(controllerId)));
        assertEquals(2,store.peer(controllerId).getInt("protocolVersion"));
        assertFalse(PeerGrantPolicy.allows(store.peer(controllerId),"sms.page",false));
        try{manager.accept(replyQr);fail("replayed QR must fail");}catch(SecurityException expected){}
    }
    @After public void tearDown()throws Exception{PeerCenterConnector.closePeer(controllerId);server.close();store.revoke(controllerId);Arrays.fill(root,(byte)0);}
    private JSONObject rpc(JSONObject req)throws Exception{
        return rpc(UUID.randomUUID().toString(),req);
    }
    private JSONObject rpc(String id,JSONObject req)throws Exception{
        String to=store.myId(),helloId=UUID.randomUUID().toString();long expires=System.currentTimeMillis()/1000+120;KeyPair eph=PeerCrypto.ephemeral();
        try(Socket socket=new Socket("127.0.0.1",server.port())){socket.setSoTimeout(10000);
            DirectPeerTransport.write(socket.getOutputStream(),PeerProtocol.seal(root,controllerId,to,helloId,expires,new JSONObject().put("action","hello").put("ephemeral",PeerCrypto.b64(eph.getPublic().getEncoded()))));
            JSONObject answer=PeerProtocol.open(root,to,controllerId,DirectPeerTransport.read(socket.getInputStream()),System.currentTimeMillis()/1000);
            byte[] session=PeerCrypto.derive(PeerCrypto.dh(eph.getPrivate(),PeerCrypto.publicKey(answer.getString("ephemeral"))),root,"session|"+controllerId+"|"+to+"|"+helloId);
            try{DirectPeerTransport.write(socket.getOutputStream(),PeerProtocol.seal(session,controllerId,to,id,expires,req));return PeerProtocol.open(session,to,controllerId,DirectPeerTransport.read(socket.getInputStream()),System.currentTimeMillis()/1000);}finally{Arrays.fill(session,(byte)0);}
        }
    }
    @Test public void testRelayLiteCiphertextQueueAndScopedAcknowledgement()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RelayLiteMailbox box=RelayLiteMailbox.get(c);
        box.setEnabled(true);
        try{
            long now=System.currentTimeMillis()/1000;
            String id=UUID.randomUUID().toString();
            JSONObject inner=PeerOfflineProtocol.seal(root,controllerId,store.myId(),id,now,
                now+600,new JSONObject().put("action","device.state").put("marker","sensitive-sandbox-data"));
            JSONObject accepted=box.enqueue(controllerId,store.myId(),inner);
            assertEquals("accepted",accepted.getString("state"));
            assertFalse(accepted.getBoolean("duplicate"));
            assertTrue(box.enqueue(controllerId,store.myId(),inner).getBoolean("duplicate"));
            JSONObject messages=box.pull(store.myId(),4);
            assertEquals(1,messages.getJSONArray("items").length());
            JSONObject first=messages.getJSONArray("items").getJSONObject(0);
            assertFalse(first.toString().contains("sensitive-sandbox-data"));
            assertEquals("sensitive-sandbox-data",PeerOfflineProtocol.open(root,controllerId,
                store.myId(),first.getJSONObject("frame"),now).getString("marker"));
            assertFalse(box.ack(controllerId,controllerId,id));
            assertTrue(box.ack(store.myId(),controllerId,id));
            assertEquals(0,box.pull(store.myId(),4).getJSONArray("items").length());
        }finally{box.setEnabled(false);}
    }

    @Test public void testRelayLiteOutboundIdempotenceAfterRetry()throws Exception{
        JSONObject body=new JSONObject().put("action","device.state");
        long now=System.currentTimeMillis()/1000,expires=now+600;
        String id=UUID.randomUUID().toString();
        JSONObject first=store.liteOutbound(controllerId,id,body,expires);
        JSONObject retry=store.liteOutbound(controllerId,id,body,expires);
        assertEquals(first.toString(),retry.toString());
        try{
            store.liteOutbound(controllerId,id,new JSONObject().put("action","sms.send"),expires);
            fail("mutated request reused a logical id");
        }catch(SecurityException expected){}
        store.clearLiteOutbound(controllerId,id);
    }
    @Test public void testRelayLitePoisonHeadsDoNotStarveOtherSources()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();RelayLiteMailbox box=RelayLiteMailbox.get(c);
        String[] sources={"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","cccccccccccccccccccccccccccccccc"};
        String target=store.myId();long now=System.currentTimeMillis()/1000;box.setEnabled(true);
        Map<String,JSONObject> frames=new HashMap<>();
        try{
            for(String source:sources)for(int i=0;i<2;i++){
                String id=source+"-"+i;
                JSONObject frame=PeerOfflineProtocol.seal(root,source,target,id,now,now+600,new JSONObject().put("action","device.state"));
                box.enqueue(source,target,frame);frames.put(id,frame);
            }
            JSONArray first=box.pull(target,2).getJSONArray("items");assertEquals(2,first.length());
            assertNotEquals(first.getJSONObject(0).getString("source"),first.getJSONObject(1).getString("source"));
            Set<String> offered=new HashSet<>();
            for(int i=0;i<first.length();i++)offered.add(first.getJSONObject(i).getString("source"));
            JSONArray second=box.pull(target,2).getJSONArray("items");
            boolean newSource=false;for(int i=0;i<second.length();i++)if(!offered.contains(second.getJSONObject(i).getString("source")))newSource=true;
            assertTrue("unacknowledged poison heads must not starve a later source",newSource);
            JSONObject poison=first.getJSONObject(0);String source=poison.getString("source"),id=poison.getString("id");
            assertFalse(box.reject(controllerId,source,id,"invalid_envelope"));
            assertTrue(box.reject(target,source,id,"invalid_envelope"));
            JSONObject retry=box.enqueue(source,target,frames.get(id));
            assertEquals("rejected",retry.getString("state"));assertEquals("invalid_envelope",retry.getString("reason"));
            assertFalse(box.ack(target,source,id));
            try{box.enqueue(source,target,new JSONObject(frames.get(id).toString()).put("ct","changed"));fail("rejected message id rebound");}
            catch(SecurityException expected){}
            assertTrue(box.stats().getInt("rejected")>=1);
        }finally{for(String source:sources)box.revokePeer(source);box.setEnabled(false);}
    }
    @Test public void testRelayLiteCacheBindsPairAndKeepsMutationTombstones()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();RelayLiteCache cache=RelayLiteCache.get(c);
        long now=System.currentTimeMillis()/1000;String id=UUID.randomUUID().toString();
        assertNull(cache.claim(controllerId,root,id,"sent","request-digest",now+600,true,now));
        cache.complete(controllerId,root,id,"sent",new JSONObject().put("body","private-cache-fixture"));
        assertEquals("private-cache-fixture",cache.get(controllerId,root,id,"sent",now).value.getString("body"));
        try(android.database.Cursor row=cache.getReadableDatabase().rawQuery("SELECT cipher FROM entries WHERE peer=?",new String[]{controllerId})){
            assertTrue(row.moveToFirst());assertFalse(row.getString(0).contains("private-cache-fixture"));
        }
        try{cache.claim(controllerId,root,id,"sent","different-request",now+600,true,now);fail("logical id changed payload");}
        catch(SecurityException expected){}
        byte[] replaced=PeerCrypto.random(32);
        try{assertNull(cache.get(controllerId,replaced,id,"sent",now));}finally{Arrays.fill(replaced,(byte)0);}
        // Read-only RPC entries may be discarded at capacity; the dispatch
        // identity of a mutation remains until its authenticated TTL expires.
        for(int i=0;i<RelayLiteCache.MAX_PEER_ROWS+4;i++)
            cache.claim(controllerId,root,"read-"+i,"sent","read-"+i,now+600,false,now);
        assertNotNull(cache.get(controllerId,root,id,"sent",now));
        try(android.database.Cursor count=cache.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE peer=?",new String[]{controllerId})){
            count.moveToFirst();assertTrue(count.getInt(0)<=RelayLiteCache.MAX_PEER_ROWS);
        }
        new com.blackkcold.simhub.SecretStore(c).putString("p2p.relayLite.response."+controllerId+".legacy-fixture",new JSONObject().put("body","legacy-private").toString());
        store.revoke(controllerId);
        assertNull(cache.get(controllerId,root,id,"sent",now));
        assertNull(new com.blackkcold.simhub.SecretStore(c).getString("p2p.relayLite.response."+controllerId+".legacy-fixture"));
    }
    @Test public void testRelayLiteFairnessSurvivesSustainedAcknowledgements()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();RelayLiteMailbox box=RelayLiteMailbox.get(c);
        String target=store.myId(),waiting="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String[] busy={"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cccccccccccccccccccccccccccccccc"};
        long now=System.currentTimeMillis()/1000;box.setEnabled(true);
        try{
            JSONObject delayed=PeerOfflineProtocol.seal(root,waiting,target,waiting+"-retry",now,now+600,new JSONObject().put("action","device.state"));
            box.enqueue(waiting,target,delayed);
            assertEquals(waiting,box.pull(target,1).getJSONArray("items").getJSONObject(0).getString("source"));
            for(String source:busy)for(int i=0;i<4;i++)box.enqueue(source,target,
                PeerOfflineProtocol.seal(root,source,target,source+"-"+i,now,now+600,new JSONObject().put("action","device.state")));
            // Simulate the first source's failed delivery becoming eligible
            // again, without waiting 30 seconds in an emulator test.
            box.getWritableDatabase().execSQL("UPDATE envelopes SET available_at=0 WHERE source=? AND target=?",new Object[]{waiting,target});
            boolean retried=false;
            for(int round=0;round<4&&!retried;round++){
                JSONArray offered=box.pull(target,2).getJSONArray("items");
                for(int i=0;i<offered.length();i++){
                    JSONObject row=offered.getJSONObject(i);String source=row.getString("source");
                    if(waiting.equals(source)){retried=true;continue;}
                    assertTrue(box.ack(target,source,row.getString("id")));
                    // Successful sources keep a backlog and immediately
                    // replenish it. ACK deletion must not reset their age.
                    box.enqueue(source,target,PeerOfflineProtocol.seal(root,source,target,source+"-new-"+round,now,now+600,new JSONObject().put("action","device.state")));
                }
            }
            assertTrue("continuously ACKed sources starved an eligible retry",retried);
        }finally{
            box.revokePeer(waiting);for(String source:busy)box.revokePeer(source);box.setEnabled(false);
        }
    }
    private RelayLiteClient.PreparedResponse preparedPrivateResponse()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();long now=System.currentTimeMillis()/1000;
        store.event(controllerId,UUID.randomUUID().toString(),"sms.received",now,
            new JSONObject().put("body","private-publication-fixture"));
        String id=UUID.randomUUID().toString();
        JSONObject request=PeerOfflineProtocol.seal(root,controllerId,store.myId(),id,now,now+600,new JSONObject().put("action","events.page"));
        RelayLiteClient.PreparedResponse result=RelayLiteClient.prepareResponse(c,store,controllerId,store.myId(),id,request);
        assertNotNull(result);
        assertTrue(PeerOfflineProtocol.open(root,store.myId(),controllerId,result.frame,now).toString().contains("private-publication-fixture"));
        return result;
    }
    @Test public void testRelayLitePreparedResponseCannotPublishAfterRevokeOrRepair()throws Exception{
        RelayLiteClient.PreparedResponse response=preparedPrivateResponse();JSONObject original=store.peer(controllerId);
        CountDownLatch ready=new CountDownLatch(1),publish=new CountDownLatch(1);
        AtomicInteger puts=new AtomicInteger();ExecutorService worker=Executors.newSingleThreadExecutor();
        RelayLiteClient.ResponsePublisher publisher=frame->{puts.incrementAndGet();return new JSONObject().put("state","accepted");};
        try{
            Future<Boolean> pending=worker.submit(()->{
                ready.countDown();assertTrue(publish.await(5,TimeUnit.SECONDS));
                try{RelayLiteClient.publishResponse(store,response,publisher);return true;}
                catch(SecurityException rejected){return false;}
            });
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            // The response was encrypted and cached, but relay.put has not
            // begun. Completed local revocation must invalidate this capture.
            store.revoke(controllerId);publish.countDown();assertFalse(pending.get(5,TimeUnit.SECONDS));
            assertEquals(0,puts.get());
            byte[] replacement=PeerCrypto.random(32);
            try{
                original.put("pairState","ACTIVE");store.save(original,replacement);
                try{RelayLiteClient.publishResponse(store,response,publisher);fail("old response crossed pair generation");}
                catch(SecurityException expected){}
            }finally{Arrays.fill(replacement,(byte)0);}
            assertEquals(0,puts.get());
        }finally{publish.countDown();worker.shutdownNow();}
    }
    @Test public void testRelayLiteRevocationDrainsOnlyActivePeerPublication()throws Exception{
        RelayLiteClient.PreparedResponse response=preparedPrivateResponse();
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(3);
        try{
            Future<?> sending=workers.submit(()->{
                RelayLiteClient.publishResponse(store,response,frame->{
                    entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));
                    return new JSONObject().put("state","accepted");
                });return null;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> revoking=workers.submit(()->{store.revoke(controllerId);return null;});
            Future<String> independent=workers.submit(()->{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline){
                    if("REVOKED".equals(store.peer(controllerId).optString("pairState")))return store.myId();
                    Thread.sleep(5);
                }
                throw new AssertionError("revocation did not invalidate the peer before draining");
            });
            // A global store lock held over network would block this probe.
            String observed=independent.get(4,TimeUnit.SECONDS);
            assertEquals(store.myId(),observed);
            assertFalse("revoke reported completion before publication ended",revoking.isDone());
            finish.countDown();sending.get(5,TimeUnit.SECONDS);revoking.get(5,TimeUnit.SECONDS);
            try{store.beginPublication(controllerId,response.generation);fail("publication after completed revoke");}
            catch(SecurityException expected){}
        }finally{finish.countDown();workers.shutdownNow();}
    }
    @Test public void testRelayLitePayloadLimitEvictsContentButNotDispatchIdentity()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();RelayLiteCache cache=RelayLiteCache.get(c);
        long now=System.currentTimeMillis()/1000;char[] text=new char[48000];Arrays.fill(text,'z');
        JSONObject payload=new JSONObject().put("body",new String(text));
        for(int i=0;i<52;i++){
            String id="bounded-payload-"+i;
            assertNull(cache.claim(controllerId,root,id,"sent",id,now+600,true,now));
            cache.complete(controllerId,root,id,"sent",payload);
        }
        RelayLiteCache.Entry tombstone=cache.claim(controllerId,root,"bounded-payload-0","sent","bounded-payload-0",now+600,true,now);
        assertNotNull(tombstone);assertNull(tombstone.value);assertEquals("evicted",tombstone.state);
        try(android.database.Cursor bytes=cache.getReadableDatabase().rawQuery("SELECT SUM(bytes) FROM entries WHERE peer=?",new String[]{controllerId})){
            bytes.moveToFirst();assertTrue(bytes.getLong(0)<=RelayLiteCache.MAX_PEER_BYTES);
        }
        assertTrue(cache.stats(now).getLong("bytes")<=RelayLiteCache.MAX_BYTES);
        assertNull(cache.get(controllerId,root,"bounded-payload-0","sent",now+601));
    }
    @Test public void testRelayLiteLegacyPreferencesRetireAfterOneOfflineWindow()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();RelayLiteCache cache=RelayLiteCache.get(c);
        long now=System.currentTimeMillis()/1000;
        cache.getWritableDatabase().execSQL("UPDATE metadata SET value=? WHERE name='legacyRetireAt'",new Object[]{now+60});
        String name="p2p.relayLite.response."+controllerId+".legacy-expiry-fixture";
        new com.blackkcold.simhub.SecretStore(c).putString(name,new JSONObject().put("state","submitted").toString());
        try{
            assertEquals("submitted",cache.legacy(controllerId,"legacy-expiry-fixture","response",now).getString("state"));
            assertNull(cache.legacy(controllerId,"legacy-expiry-fixture","response",now+61));
            assertNull(new com.blackkcold.simhub.SecretStore(c).getString(name));
        }finally{cache.getWritableDatabase().execSQL("UPDATE metadata SET value=? WHERE name='legacyRetireAt'",new Object[]{now+PeerOfflineProtocol.MAX_TTL_SECONDS});}
    }
    @Test public void testAuthenticatedDirectStateAndRevocation()throws Exception{
        assertEquals("succeeded",rpc(new JSONObject().put("action","device.state")).getString("state"));
        store.revoke(controllerId);try{rpc(new JSONObject().put("action","device.state"));fail("revoked peer accepted");}catch(java.io.IOException expected){}
    }
    @Test public void testUnauthorizedSendNeverDispatches()throws Exception{
        try{rpc(new JSONObject().put("action","sms.send"));fail("unauthorized send accepted");}catch(java.io.IOException expected){}
    }
    @Test public void testDurableReceiptIdempotenceAndPayloadBinding()throws Exception{
        String id=UUID.randomUUID().toString();assertNull(store.claim(controllerId,id,"payload-a"));
        assertEquals("indeterminate",store.claim(controllerId,id,"payload-a").getString("state"));
        store.finish(controllerId,id,new JSONObject().put("state","submitted"));assertEquals("submitted",store.claim(controllerId,id,"payload-a").getString("state"));
        try{store.claim(controllerId,id,"payload-b");fail("payload rebinding accepted");}catch(SecurityException expected){}
    }
    @Test public void testSmsReceiptSurvivesRouteSwitchAndExpiry()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();long now=System.currentTimeMillis()/1000;String id=UUID.randomUUID().toString();
        // Revision zero is invalid before SmsManager; this test cannot send a real SMS.
        JSONObject req=new JSONObject().put("action","sms.send").put("to","+15550001111").put("body","sandbox-only").put("channelId","nonexistent-sandbox-channel").put("channelRevision",0).put("commandExpires",now+120);
        JSONObject sealed=PeerRelayRpc.execute(c,PeerProtocol.seal(root,controllerId,store.myId(),id,now+120,req));
        assertEquals("failed",PeerProtocol.open(root,store.myId(),controllerId,sealed,now).getJSONObject("result").getString("state"));
        assertEquals("failed",rpc(id,req).getString("state"));
        assertEquals("failed",rpc(new JSONObject().put("action","command.status").put("commandId",id)).getString("state"));
        try{new PeerRpc(store).dispatch(controllerId,id,new JSONObject(req.toString()).put("body","changed"));fail("Route switch changed original payload");}catch(SecurityException expected){}
        String expiredId=UUID.randomUUID().toString();JSONObject old=new JSONObject(req.toString()).put("commandExpires",now-1);
        assertNull(store.claim(controllerId,expiredId,PeerCrypto.digest(PeerCrypto.utf8(old.toString()))));store.finish(controllerId,expiredId,new JSONObject().put("state","submitted"));
        assertEquals("submitted",new PeerRpc(store).dispatch(controllerId,expiredId,old).getString("state"));
    }
    @Test public void testEncryptedEventsRequirePeerScopedAck()throws Exception{
        String event="sandbox-event-"+UUID.randomUUID();store.event(controllerId,event,"sms.received",System.currentTimeMillis()/1000,new JSONObject().put("body","private sandbox event"));
        JSONObject page=rpc(new JSONObject().put("action","events.page"));JSONArray rows=page.getJSONArray("events");assertEquals(1,rows.length());assertEquals("private sandbox event",rows.getJSONObject(0).getJSONObject("payload").getString("body"));long seq=rows.getJSONObject(0).getLong("seq");
        store.ackEvents("other-peer-1234567890",new JSONArray().put(seq));assertEquals(1,store.events(controllerId,0).getJSONArray("events").length());
        assertEquals("acknowledged",rpc(new JSONObject().put("action","events.ack").put("sequences",new JSONArray().put(seq))).getString("state"));assertEquals(0,store.events(controllerId,0).getJSONArray("events").length());
    }
    @Test public void testRelayResetPreservesPeerKeysPendingSmsAndReceipts()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.blackkcold.simhub.AgentConfig config=new com.blackkcold.simhub.AgentConfig(c);
        byte[] nodeKey=PeerCrypto.random(32);config.setNodeEnrollment("https://legacy.invalid","legacy-sandbox","Legacy node","sandbox-token-with-at-least-forty-characters-1234567890",com.blackkcold.simhub.CryptoBox.keyId(nodeKey),nodeKey);
        com.blackkcold.simhub.LocalStore local=com.blackkcold.simhub.LocalStore.get(c);String id="p2p-"+UUID.randomUUID();
        try{
            assertTrue(local.claimCommand(id,"sms.send"));
            JSONObject payload=new JSONObject().put("commandId",id).put("body","private pending sandbox SMS");
            JSONObject cipher=com.blackkcold.simhub.PendingSmsCrypto.encrypt(c,payload);
            assertEquals("simhub-pending-sms-v1",cipher.getString("domain"));
            local.createPendingSms(id,"",1,cipher,-1);
            local.queueCommandAck(id,"submitted",new JSONObject());assertEquals(0,local.pendingCommandAckCount());
            local.resetForReenrollment();config.clearEnrollment();
            assertFalse(config.isEnrolled());assertTrue(Arrays.equals(root,store.key(controllerId)));
            assertEquals("claimed",local.commandState(id));
            com.blackkcold.simhub.LocalStore.PendingStatus status=local.recordSentPart(id,0,true,0);
            assertTrue(status.exists);assertEquals("private pending sandbox SMS",com.blackkcold.simhub.PendingSmsCrypto.decrypt(c,status.eventCipher).getString("body"));
        }finally{local.removePendingSms(id);config.clearEnrollment();Arrays.fill(nodeKey,(byte)0);}
    }
    @Test public void testRelayCarrierStillRequiresP2PGrant()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();String id=UUID.randomUUID().toString();long now=System.currentTimeMillis()/1000;
        JSONObject request=new JSONObject().put("action","device.state");
        JSONObject response=PeerRelayRpc.execute(c,PeerProtocol.seal(root,controllerId,store.myId(),id,now+120,request));
        JSONObject result=PeerProtocol.open(root,store.myId(),controllerId,response,now);
        assertEquals("succeeded",result.getJSONObject("result").getString("state"));assertEquals(PeerCrypto.digest(PeerCrypto.utf8(request.toString())),result.getString("requestDigest"));
        try{PeerRelayRpc.execute(c,PeerProtocol.seal(root,controllerId,store.myId(),UUID.randomUUID().toString(),now+120,new JSONObject().put("action","sms.send")));fail("Relay bypassed P2P send grant");}catch(SecurityException expected){}
        store.revoke(controllerId);try{PeerRelayRpc.execute(c,PeerProtocol.seal(root,controllerId,store.myId(),id,now+120,request));fail("Relay bypassed revocation");}catch(SecurityException expected){}
    }
    @Test public void testConfiguredRelayIsPreferredAndP2PIsBackup()throws Exception{
        JSONObject profile=store.peer(controllerId);
        // Hybrid routing needs both an explicit mixed policy and a separately
        // authorized legacy Relay. A P2P_ONLY profile must never select Relay.
        profile.put("policy","P2P_RELAY_FALLBACK").put("relayConfigured",true);
        store.update(profile);int[] calls={0,0};
        ConnectionCoordinator coordinator=new ConnectionCoordinator(store);
        coordinator.relay(new HubTransport(){public String route(){return "LEGACY_RELAY";}public JSONObject request(JSONObject p,String id,JSONObject req)throws Exception{calls[0]++;if(calls[0]>1)throw new java.io.IOException("relay unavailable");return new JSONObject().put("path","relay");}});
        coordinator.internet(new HubTransport(){public String route(){return "DIRECT";}public JSONObject request(JSONObject p,String id,JSONObject req)throws Exception{calls[1]++;return new JSONObject().put("path","p2p");}});
        assertEquals("relay",coordinator.request(controllerId,UUID.randomUUID().toString(),new JSONObject()).getString("path"));assertEquals(0,calls[1]);
        assertEquals("p2p",coordinator.request(controllerId,UUID.randomUUID().toString(),new JSONObject()).getString("path"));assertEquals(1,calls[1]);assertEquals("DIRECT",store.peer(controllerId).getString("activeTransport"));
    }
    @Test public void testUnconfiguredRelayIsNeverUsed()throws Exception{
        ConnectionCoordinator coordinator=new ConnectionCoordinator(store);int[] calls={0};
        coordinator.relay(new HubTransport(){public String route(){return "LEGACY_RELAY";}public JSONObject request(JSONObject p,String id,JSONObject req){calls[0]++;return new JSONObject();}});
        coordinator.internet(new HubTransport(){public String route(){return "DIRECT";}public JSONObject request(JSONObject p,String id,JSONObject req)throws Exception{throw new java.io.IOException("p2p blocked");}});
        try{coordinator.request(controllerId,UUID.randomUUID().toString(),new JSONObject());fail("Unconfigured backup used");}catch(java.io.IOException expected){}
        assertEquals(0,calls[0]);assertEquals("OFFLINE",store.peer(controllerId).getString("activeTransport"));
    }
    @Test public void testStaleMetadataCannotRestoreRevokedGrant()throws Exception{
        JSONObject old=store.peer(controllerId);store.revoke(controllerId);old.put("discoveryState","READY");
        try{store.update(old);fail("Stale network callback restored authorization");}catch(SecurityException expected){}
        assertEquals("REVOKED",store.peer(controllerId).getString("pairState"));
    }
    @Test public void testNativeEngineRequiresTrustedCenterAndNeverOffersTurn()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // The engine is native and data-only. No WebView, no bundled script, no remote code.
        PeerNativeRtcTransport transport=new PeerNativeRtcTransport(c,store.peer(controllerId),false,signal->{});
        try{
            transport.init(new JSONArray(), false);
            assertTrue("native WebRTC engine must initialize a real PeerConnection",transport.engineReady());
        }finally{transport.close();}
        String ice=PeerCenterProtocol.iceServers(c,controllerId).toString();
        assertTrue(ice.contains("stun:"));assertFalse(ice.contains("turn:"));assertFalse(ice.contains("turns:"));
        // The auxiliary path is not authorized without a trusted description: an
        // enrolled address or a pairing hint is never trust.
        // Public CI packages synthetic pins for reserved .invalid hosts. This
        // description must exist, but it never authorizes arbitrary configured
        // centers or silently enrolls a separate Relay.
        com.blackkcold.simhub.CenterTrust synthetic =
                com.blackkcold.simhub.CenterTrustStore.get(c).effective();
        assertNotNull("preview build must load synthetic test trust", synthetic);
        assertFalse(synthetic.covers(com.blackkcold.simhub.CenterTrust.ROLE_CONTROLLER,
                "https://untrusted.invalid"));
        assertEquals("P2P_ONLY",store.peer(controllerId).getString("policy"));
        assertFalse(store.peer(controllerId).optBoolean("signalingConsent"));
        try{PeerCenterConnector.ensure(c,controllerId);fail("untrusted center enabled the auxiliary path");}catch(SecurityException expected){}
        // The upgrade that retires the old discovery flag is idempotent and never
        // turns the new path on by itself.
        assertTrue(store.migrateSignalingConsent()>=0);
        assertEquals(0,store.migrateSignalingConsent());
        assertFalse(store.peer(controllerId).optBoolean("signalingConsent"));
        assertEquals("",store.peer(controllerId).optString("signalingCenterBinding"));
    }
    @Test public void testSignedCenterBindingQrRequiresConsentAndRegisteredBinding()throws Exception{
        PeerPairingManager manager=new PeerPairingManager(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try{manager.signalingBindingQr(controllerId);fail("binding QR without consent");}
        catch(SecurityException expected){}
        store.setSignaling(controllerId,true,"",1);
        try{manager.signalingBindingQr(controllerId);fail("binding QR before registration");}
        catch(SecurityException expected){}
        String binding=UUID.randomUUID().toString();
        store.setSignaling(controllerId,true,binding,2);
        String qr=manager.signalingBindingQr(controllerId);
        assertEquals("signal-binding",PeerPairingManager.kind(qr));
        JSONObject envelope=new JSONObject(new String(PeerCrypto.unb64(qr.substring(12)),java.nio.charset.StandardCharsets.UTF_8));
        String signed=envelope.getString("body");
        JSONObject proof=new JSONObject(new String(PeerCrypto.unb64(signed),java.nio.charset.StandardCharsets.UTF_8));
        PeerCrypto.verify(store.publicKey(),signed,envelope.getString("signature"));
        assertEquals(store.myId(),proof.getString("nodeId"));
        assertEquals(controllerId,proof.getString("controllerId"));
        assertEquals(binding,proof.getString("bindingId"));
        proof.put("bindingId",UUID.randomUUID().toString());
        envelope.put("body",PeerCrypto.b64(PeerCrypto.utf8(proof.toString())));
        try{
            PeerPairingManager.kind("simhub-peer:"+PeerCrypto.b64(PeerCrypto.utf8(envelope.toString())));
            fail("tampered binding QR signature accepted");
        }catch(SecurityException expected){}
    }
    @Test public void testMoreThan1000ProviderRowsThroughEncryptedPagination()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();List<Uri> inserted=new ArrayList<>();
        try{
            long date=System.currentTimeMillis();for(int i=0;i<1205;i++){ContentValues row=new ContentValues();row.put("address","+15550001111");row.put("body","Sandbox history "+i);row.put("date",date);row.put("type",1);row.put("read",1);Uri uri=c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI,row);assertNotNull(uri);inserted.add(uri);}
            Set<String> seen=new HashSet<>();JSONObject cursor=null;int pages=0;
            do{JSONObject request=new JSONObject().put("action","sms.page").put("limit",100);if(cursor!=null)request.put("cursor",cursor);JSONObject result=rpc(request);assertEquals("succeeded",result.getString("state"));JSONArray messages=result.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);assertTrue("duplicate history row",seen.add(message.getString("id")));assertEquals("reply address must survive P2P history", "+15550001111",message.getString("address"));}pages++;cursor=result.optBoolean("hasMore")?result.getJSONObject("cursor"):null;}while(cursor!=null&&pages<20);
            assertEquals(1205,seen.size());assertEquals(13,pages);
        }finally{for(Uri uri:inserted)c.getContentResolver().delete(uri,null,null);}
    }
}
