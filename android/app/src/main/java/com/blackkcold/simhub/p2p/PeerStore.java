package com.blackkcold.simhub.p2p;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import com.blackkcold.simhub.SecretStore;
import org.json.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.List;

/** Entirely separate from AgentConfig, ControllerProfiles, enrollment and Global Vault. */
public final class PeerStore extends SQLiteOpenHelper {
    private static PeerStore INSTANCE;
    private final Context context;
    private static final String IDENTITY="simhub-p2p-identity-v1";
    public static synchronized PeerStore get(Context c){if(INSTANCE==null)INSTANCE=new PeerStore(c.getApplicationContext());return INSTANCE;}
    private PeerStore(Context c){super(c,"simhub-peers-v1.db",null,3);context=c;setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY,profile TEXT NOT NULL)");
        db.execSQL("CREATE TABLE receipts(peer TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,result TEXT,state TEXT NOT NULL,PRIMARY KEY(peer,id))");
        createEvents(db);
        createLiteOutbox(db);
        db.execSQL("CREATE TABLE outbox(peer TEXT NOT NULL,id TEXT NOT NULL,cipher TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(peer,id))");
    }
    public void onUpgrade(SQLiteDatabase db,int a,int b){
        if(a<2)createEvents(db);
        if(a<3)createLiteOutbox(db);
    }
    private static void createLiteOutbox(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS lite_outbox(peer TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL,frame TEXT NOT NULL,PRIMARY KEY(peer,id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS lite_outbox_expiry ON lite_outbox(expires)");
    }
    private static void createEvents(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS peer_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,peer TEXT NOT NULL,event TEXT NOT NULL,kind TEXT NOT NULL,cipher TEXT NOT NULL,occurred INTEGER NOT NULL,UNIQUE(peer,event))");}

    public synchronized KeyPair identity()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(IDENTITY)){
            KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(IDENTITY,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());g.generateKeyPair();
        }
        return new KeyPair(ks.getCertificate(IDENTITY).getPublicKey(),(PrivateKey)ks.getKey(IDENTITY,null));
    }
    public String publicKey()throws Exception{return PeerCrypto.b64(identity().getPublic().getEncoded());}
    public String myId()throws Exception{return PeerCrypto.digest(identity().getPublic().getEncoded());}
    public synchronized void save(JSONObject p,byte[] key)throws Exception{
        String id=p.getString("id");if(!id.equals(PeerCrypto.digest(PeerCrypto.publicKey(p.getString("publicKey")).getEncoded())))throw new SecurityException("peer_id_mismatch");
        try(Cursor existing=getReadableDatabase().rawQuery("SELECT profile FROM peers WHERE id=?",new String[]{id})){if(existing.moveToFirst()&&!"REVOKED".equals(new JSONObject(existing.getString(0)).optString("pairState")))throw new SecurityException("peer_already_known");}
        new SecretStore(context).putBytes("p2p.content."+id,key);
        ContentValues v=new ContentValues();v.put("id",id);v.put("profile",p.toString());if(getWritableDatabase().insertWithOnConflict("peers",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("peer_profile_not_durable");
    }
    public synchronized JSONObject peer(String id)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT profile FROM peers WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("peer_not_authorized");return new JSONObject(c.getString(0));}}
    public synchronized JSONArray list(){JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT profile FROM peers ORDER BY id",null)){while(c.moveToNext())try{a.put(new JSONObject(c.getString(0)));}catch(Exception e){throw new IllegalStateException(e);}}return a;}
    public synchronized void update(JSONObject p)throws Exception{JSONObject previous=peer(p.getString("id"));if("REVOKED".equals(previous.optString("pairState"))&&!"REVOKED".equals(p.optString("pairState")))throw new SecurityException("peer_revoked");for(String field:List.of("publicKey","localRole","permissions","protocolVersion","grantsToRemote","grantsFromRemote"))if(!previous.optString(field).equals(p.optString(field)))throw new SecurityException("immutable_peer_grant");ContentValues v=new ContentValues();v.put("profile",p.toString());if(getWritableDatabase().update("peers",v,"id=?",new String[]{p.getString("id")})!=1)throw new SecurityException("peer_not_authorized");}
    public byte[] key(String id)throws Exception{JSONObject p=peer(id);if("REVOKED".equals(p.optString("pairState")))throw new SecurityException("peer_revoked");byte[] key=new SecretStore(context).getBytes("p2p.content."+id);if(key==null||key.length!=32)throw new SecurityException("peer_key_unavailable");return key;}
    public synchronized void revoke(String id)throws Exception{JSONObject p=peer(id);p.put("pairState","REVOKED").put("activeTransport","OFFLINE");update(p);new SecretStore(context).remove("p2p.content."+id);getWritableDatabase().delete("outbox","peer=?",new String[]{id});getWritableDatabase().delete("peer_events","peer=?",new String[]{id});getWritableDatabase().delete("lite_outbox","peer=?",new String[]{id});RelayLiteMailbox.get(context).revokePeer(id);}
    /** Atomic durable claim. A crash after dispatch remains 'indeterminate', never blindly resends. */
    public synchronized JSONObject receipt(String peer,String id,String digest)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT digest,result FROM receipts WHERE peer=? AND id=?",new String[]{peer,id})){
            if(!c.moveToFirst())return null;
            if(digest!=null&&!digest.equals(c.getString(0)))throw new SecurityException("peer_request_id_reused");
            return c.isNull(1)?new JSONObject().put("state","indeterminate").put("reason","previous_dispatch_unconfirmed"):new JSONObject(c.getString(1));
        }
    }
    public synchronized JSONObject claim(String peer,String id,String digest)throws Exception{
        JSONObject previous=receipt(peer,id,digest);if(previous!=null)return previous;
        ContentValues v=new ContentValues();v.put("peer",peer);v.put("id",id);v.put("digest",digest);v.put("state","claimed");
        try(Cursor total=getReadableDatabase().rawQuery("SELECT count(*) FROM receipts WHERE peer=?",new String[]{peer})){total.moveToFirst();if(total.getInt(0)>=100000)throw new SecurityException("peer_command_capacity");}
        if(getWritableDatabase().insertWithOnConflict("receipts",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT digest,result FROM receipts WHERE peer=? AND id=?",new String[]{peer,id})){
            if(!c.moveToFirst()||!digest.equals(c.getString(0)))throw new SecurityException("peer_request_id_reused");
            return c.isNull(1)?new JSONObject().put("state","indeterminate").put("reason","previous_dispatch_unconfirmed"):new JSONObject(c.getString(1));
        }
    }
    public synchronized void finish(String peer,String id,JSONObject result)throws Exception{ContentValues v=new ContentValues();v.put("result",result.toString());v.put("state",result.optString("state","succeeded"));if(getWritableDatabase().update("receipts",v,"peer=? AND id=?",new String[]{peer,id})!=1)throw new IllegalStateException("peer_receipt_missing");}
    public synchronized boolean event(String peer,String id,String kind,long occurred,JSONObject payload)throws Exception{
        try(Cursor check=getReadableDatabase().rawQuery("SELECT seq FROM peer_events WHERE peer=? AND event=?",new String[]{peer,id})){if(check.moveToFirst())return true;}
        try(Cursor count=getReadableDatabase().rawQuery("SELECT count(*) FROM peer_events WHERE peer=?",new String[]{peer})){count.moveToFirst();if(count.getInt(0)>=10000)return false;}
        byte[] key=key(peer),nonce=PeerCrypto.random(12);
        try{
            JSONObject cipher=new JSONObject().put("iv",PeerCrypto.b64(nonce)).put("ct",PeerCrypto.b64(PeerCrypto.crypt(true,key,nonce,"event|"+peer+"|"+id+"|"+kind,PeerCrypto.utf8(payload.toString()))));
            ContentValues values=new ContentValues();values.put("peer",peer);values.put("event",id);values.put("kind",kind);values.put("cipher",cipher.toString());values.put("occurred",occurred);getWritableDatabase().insertOrThrow("peer_events",null,values);return true;
        }finally{java.util.Arrays.fill(key,(byte)0);}
    }
    public synchronized JSONObject events(String peer,long after)throws Exception{
        return events(peer,after,100,300000);
    }
    public synchronized JSONObject events(String peer,long after,int requestedLimit,int requestedBytes)throws Exception{
        int limit=Math.max(1,Math.min(100,requestedLimit));
        int maxBytes=Math.max(16000,Math.min(300000,requestedBytes));
        JSONArray rows=new JSONArray();int bytes=0;byte[] key=key(peer);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,event,kind,cipher,occurred FROM peer_events WHERE peer=? AND seq>? ORDER BY seq LIMIT ?",new String[]{peer,String.valueOf(Math.max(0,after)),String.valueOf(limit)})){
            while(c.moveToNext()){
                JSONObject cipher=new JSONObject(c.getString(3));JSONObject payload=new JSONObject(new String(PeerCrypto.crypt(false,key,PeerCrypto.unb64(cipher.getString("iv")),"event|"+peer+"|"+c.getString(1)+"|"+c.getString(2),PeerCrypto.unb64(cipher.getString("ct"))),java.nio.charset.StandardCharsets.UTF_8));
                JSONObject row=new JSONObject().put("seq",c.getLong(0)).put("id",c.getString(1)).put("kind",c.getString(2)).put("occurred",c.getLong(4)).put("payload",payload);
                int size=PeerCrypto.utf8(row.toString()).length;if(bytes+size>maxBytes)break;bytes+=size;rows.put(row);
            }
        }finally{java.util.Arrays.fill(key,(byte)0);}
        return new JSONObject().put("state","succeeded").put("events",rows);
    }
    public synchronized void ackEvents(String peer,JSONArray sequences)throws Exception{
        if(sequences.length()>100)throw new SecurityException("too_many_event_acks");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(int i=0;i<sequences.length();i++)db.delete("peer_events","peer=? AND seq=?",new String[]{peer,String.valueOf(sequences.getLong(i))});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    /**
     * Retries MUST reuse the exact authenticated ciphertext and IV. Regenerating
     * an AEAD frame for the same logical id is not a valid idempotent retry.
     */
    public synchronized JSONObject liteOutbound(String peer,String id,JSONObject request,long expiry)throws Exception{
        long now=System.currentTimeMillis()/1000;
        if(expiry<=now||expiry>now+PeerOfflineProtocol.MAX_TTL_SECONDS)
            throw new SecurityException("relay_lite_expiry_invalid");
        String digest=PeerCrypto.digest(PeerCrypto.utf8(request.toString()));
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("lite_outbox","expires<=?",new String[]{String.valueOf(now)});
            try(Cursor c=db.rawQuery("SELECT digest,expires,frame FROM lite_outbox WHERE peer=? AND id=?",
                new String[]{peer,id})){
                if(c.moveToFirst()){
                    if(!digest.equals(c.getString(0)))throw new SecurityException("relay_lite_request_id_reused");
                    if(c.getLong(1)<=now)throw new SecurityException("relay_lite_request_expired");
                    JSONObject saved=new JSONObject(c.getString(2));
                    db.setTransactionSuccessful();
                    return saved;
                }
            }
            try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM lite_outbox WHERE peer=?",new String[]{peer})){
                if(count.moveToFirst()&&count.getLong(0)>=256)
                    throw new IllegalStateException("relay_lite_outbound_full");
            }
            byte[] root=key(peer);
            JSONObject sealed;
            try{sealed=PeerOfflineProtocol.seal(root,myId(),peer,id,now,expiry,request);}
            finally{java.util.Arrays.fill(root,(byte)0);}
            ContentValues row=new ContentValues();
            row.put("peer",peer);row.put("id",id);row.put("digest",digest);
            row.put("expires",expiry);row.put("frame",sealed.toString());
            db.insertOrThrow("lite_outbox",null,row);
            db.setTransactionSuccessful();
            return sealed;
        }finally{db.endTransaction();}
    }
    public synchronized void clearLiteOutbound(String peer,String id){
        getWritableDatabase().delete("lite_outbox","peer=? AND id=?",new String[]{peer,id});
    }
    public synchronized void queueOutbound(String peer,String id,JSONObject request)throws Exception{
        byte[] key=key(peer);long expires=request.getLong("commandExpires");try{
            JSONObject frame=PeerProtocol.seal(key,myId(),peer,id,expires,request);ContentValues values=new ContentValues();values.put("peer",peer);values.put("id",id);values.put("cipher",frame.toString());values.put("expires",expires);
            getWritableDatabase().insertWithOnConflict("outbox",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        }finally{java.util.Arrays.fill(key,(byte)0);}
    }
    public synchronized JSONArray outbound(String peer)throws Exception{
        JSONArray rows=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,cipher,expires FROM outbox WHERE peer=? ORDER BY expires LIMIT 16",new String[]{peer})){
            while(c.moveToNext()){
                JSONObject row=new JSONObject().put("id",c.getString(0)).put("expires",c.getLong(2));
                if(c.getLong(2)<System.currentTimeMillis()/1000)row.put("state","expired");
                else{byte[] key=key(peer);try{row.put("request",PeerProtocol.open(key,myId(),peer,new JSONObject(c.getString(1)),System.currentTimeMillis()/1000));}finally{java.util.Arrays.fill(key,(byte)0);}}
                rows.put(row);
            }
        }return rows;
    }
    public synchronized JSONObject outbound(String peer,String id)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT cipher,expires FROM outbox WHERE peer=? AND id=?",new String[]{peer,id})){
            if(!c.moveToFirst())return null;
            if(c.getLong(1)<System.currentTimeMillis()/1000)return new JSONObject().put("state","expired");
            byte[] key=key(peer);try{return new JSONObject().put("request",PeerProtocol.open(key,myId(),peer,new JSONObject(c.getString(0)),System.currentTimeMillis()/1000));}finally{java.util.Arrays.fill(key,(byte)0);}
        }
    }
    public synchronized void clearOutbound(String peer,String id){getWritableDatabase().delete("outbox","peer=? AND id=?",new String[]{peer,id});}
    public synchronized void markTransport(String id,String route)throws Exception{JSONObject p=peer(id);if(!"ACTIVE".equals(p.optString("pairState")))return;p.put("activeTransport",route).put("lastSyncAt",System.currentTimeMillis()/1000);update(p);}
    /**
     * Center-assisted signaling state for one pair. Authorization is explicit and
     * never inferred from the retired public-discovery flag.
     */
    public synchronized JSONObject signaling(String id)throws Exception{
        JSONObject p=peer(id);
        return new JSONObject().put("mode",PeerSignalingProtocol.MODE)
            .put("consent",p.optBoolean("signalingConsent",false))
            .put("bindingId",p.optString("signalingCenterBinding",""))
            .put("revision",p.optInt("signalingConsentRevision",0));
    }
    /** Consent is a distinct authorization; a change invalidates pending sessions. */
    public synchronized void setSignaling(String id,boolean consent,String bindingId,int revision)throws Exception{
        if(consent&&(revision<1||revision>2147483647))throw new SecurityException("signaling_revision_invalid");
        JSONObject p=peer(id);
        int previous=p.optInt("signalingConsentRevision",0);
        if(p.has("signalingConsentRevision")&&revision<=previous)throw new SecurityException("signaling_revision_stale");
        p.put("signalingConsent",consent).put("signalingConsentRevision",revision).put("signalingCenterBinding",bindingId==null?"":bindingId);
        update(p);
    }
    /**
     * Idempotent upgrade of pre-signaling profiles. The retired public-discovery flag
     * is kept for audit but never enables the new path: signaling consent starts false.
     */
    public synchronized int migrateSignalingConsent()throws Exception{
        int changed=0;JSONArray peers=list();
        for(int i=0;i<peers.length();i++){
            JSONObject p=peers.getJSONObject(i);
            if(p.has("signalingConsent"))continue;
            p.put("signalingConsent",false).put("signalingConsentRevision",0).put("signalingCenterBinding","");
            if(p.has("publicDiscoveryEnabled"))p.put("publicDiscoveryRetired",p.optBoolean("publicDiscoveryEnabled"));
            update(p);changed++;
        }
        return changed;
    }
    public Context context(){return context;}
}
