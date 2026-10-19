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
            assertEquals("p2p-local-pending-v1",cipher.getString("domain"));
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
