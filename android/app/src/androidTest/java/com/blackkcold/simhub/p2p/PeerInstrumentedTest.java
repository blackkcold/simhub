package com.blackkcold.simhub.p2p;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
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
    private PeerStore store;
    private PeerNodeServer server;
    private String controllerId;
    private byte[] root;
    @Before public void setUp()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();store=PeerStore.get(c);server=new PeerNodeServer(store);new Thread(server::run).start();
        KeyPair identity=PeerCrypto.ephemeral(),ephemeral=PeerCrypto.ephemeral();String pub=PeerCrypto.b64(identity.getPublic().getEncoded()),epub=PeerCrypto.b64(ephemeral.getPublic().getEncoded());controllerId=PeerCrypto.digest(identity.getPublic().getEncoded());
        PeerPairingManager manager=new PeerPairingManager(c);String offerQr=manager.offer("Sandbox Node",false,new JSONArray());
        JSONObject qr=new JSONObject(new String(PeerCrypto.unb64(offerQr.substring(12)),java.nio.charset.StandardCharsets.UTF_8));String offerBody=qr.getString("body");
        JSONObject offer=new JSONObject(new String(PeerCrypto.unb64(offerBody),java.nio.charset.StandardCharsets.UTF_8));PeerCrypto.verify(offer.getString("publicKey"),offerBody,qr.getString("signature"));
        JSONObject response=new JSONObject().put("v",1).put("kind","response").put("offer",offerBody).put("offerHash",PeerCrypto.digest(PeerCrypto.utf8(offerBody))).put("publicKey",pub).put("ephemeral",epub).put("name","Sandbox Controller");
        String reply=PeerCrypto.b64(PeerCrypto.utf8(response.toString()));String replyQr="simhub-peer:"+PeerCrypto.b64(PeerCrypto.utf8(new JSONObject().put("body",reply).put("signature",PeerCrypto.sign(identity.getPrivate(),reply)).toString()));
        String transcript=PeerCrypto.pairTranscript(offer.getString("sid"),offer.getLong("expires"),offer.getString("publicKey"),offer.getString("ephemeral"),pub,epub,offer.getString("permissions"));
        root=PeerCrypto.derive(PeerCrypto.dh(ephemeral.getPrivate(),PeerCrypto.publicKey(offer.getString("ephemeral"))),PeerCrypto.utf8(PeerCrypto.digest(PeerCrypto.utf8(transcript))),"content-root");
        assertEquals(controllerId,manager.accept(replyQr));assertTrue(Arrays.equals(root,store.key(controllerId)));
        try{manager.accept(replyQr);fail("replayed QR must fail");}catch(SecurityException expected){}
    }
    @After public void tearDown()throws Exception{server.close();store.revoke(controllerId);Arrays.fill(root,(byte)0);}
    private JSONObject rpc(JSONObject req)throws Exception{
        String to=store.myId(),helloId=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()/1000+120;KeyPair eph=PeerCrypto.ephemeral();
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
    @Test public void testEncryptedEventsRequirePeerScopedAck()throws Exception{
        String event="sandbox-event-"+UUID.randomUUID();store.event(controllerId,event,"sms.received",System.currentTimeMillis()/1000,new JSONObject().put("body","private sandbox event"));
        JSONObject page=rpc(new JSONObject().put("action","events.page"));JSONArray rows=page.getJSONArray("events");assertEquals(1,rows.length());assertEquals("private sandbox event",rows.getJSONObject(0).getJSONObject("payload").getString("body"));long seq=rows.getJSONObject(0).getLong("seq");
        store.ackEvents("other-peer-1234567890",new JSONArray().put(seq));assertEquals(1,store.events(controllerId,0).getJSONArray("events").length());
        assertEquals("acknowledged",rpc(new JSONObject().put("action","events.ack").put("sequences",new JSONArray().put(seq))).getString("state"));assertEquals(0,store.events(controllerId,0).getJSONArray("events").length());
    }
    @Test public void testMoreThan1000ProviderRowsThroughEncryptedPagination()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();List<Uri> inserted=new ArrayList<>();
        try{
            long date=System.currentTimeMillis();for(int i=0;i<1205;i++){ContentValues row=new ContentValues();row.put("address","+15550001111");row.put("body","Sandbox history "+i);row.put("date",date);row.put("type",1);row.put("read",1);Uri uri=c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI,row);assertNotNull(uri);inserted.add(uri);}
            Set<String> seen=new HashSet<>();JSONObject cursor=null;int pages=0;
            do{JSONObject request=new JSONObject().put("action","sms.page").put("limit",100);if(cursor!=null)request.put("cursor",cursor);JSONObject result=rpc(request);assertEquals("succeeded",result.getString("state"));JSONArray messages=result.getJSONArray("messages");for(int i=0;i<messages.length();i++)assertTrue("duplicate history row",seen.add(messages.getJSONObject(i).getString("id")));pages++;cursor=result.optBoolean("hasMore")?result.getJSONObject("cursor"):null;}while(cursor!=null&&pages<20);
            assertEquals(1205,seen.size());assertEquals(13,pages);
        }finally{for(Uri uri:inserted)c.getContentResolver().delete(uri,null,null);}
    }
}
