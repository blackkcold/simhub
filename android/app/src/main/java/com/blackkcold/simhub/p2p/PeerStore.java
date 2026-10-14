package com.blackkcold.simhub.p2p;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import com.blackkcold.simhub.SecretStore;
import org.json.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** Entirely separate from AgentConfig, ControllerProfiles, enrollment and Global Vault. */
public final class PeerStore extends SQLiteOpenHelper {
    private static PeerStore INSTANCE;
    private final Context context;
    private static final String IDENTITY="simhub-p2p-identity-v1";
    public static synchronized PeerStore get(Context c){if(INSTANCE==null)INSTANCE=new PeerStore(c.getApplicationContext());return INSTANCE;}
    private PeerStore(Context c){super(c,"simhub-peers-v1.db",null,2);context=c;setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY,profile TEXT NOT NULL)");
        db.execSQL("CREATE TABLE receipts(peer TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,result TEXT,state TEXT NOT NULL,PRIMARY KEY(peer,id))");
        createEvents(db);
        db.execSQL("CREATE TABLE outbox(peer TEXT NOT NULL,id TEXT NOT NULL,cipher TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(peer,id))");
    }
    public void onUpgrade(SQLiteDatabase db,int a,int b){if(a<2)createEvents(db);}
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
    public synchronized void update(JSONObject p)throws Exception{ContentValues v=new ContentValues();v.put("profile",p.toString());if(getWritableDatabase().update("peers",v,"id=?",new String[]{p.getString("id")})!=1)throw new SecurityException("peer_not_authorized");}
    public byte[] key(String id)throws Exception{JSONObject p=peer(id);if("REVOKED".equals(p.optString("pairState")))throw new SecurityException("peer_revoked");byte[] key=new SecretStore(context).getBytes("p2p.content."+id);if(key==null||key.length!=32)throw new SecurityException("peer_key_unavailable");return key;}
    public synchronized void revoke(String id)throws Exception{JSONObject p=peer(id);p.put("pairState","REVOKED").put("activeTransport","OFFLINE");update(p);new SecretStore(context).remove("p2p.content."+id);getWritableDatabase().delete("outbox","peer=?",new String[]{id});getWritableDatabase().delete("peer_events","peer=?",new String[]{id});}
    /** Atomic durable claim. A crash after dispatch remains 'indeterminate', never blindly resends. */
    public synchronized JSONObject claim(String peer,String id,String digest)throws Exception{
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
        JSONArray rows=new JSONArray();int bytes=0;byte[] key=key(peer);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,event,kind,cipher,occurred FROM peer_events WHERE peer=? AND seq>? ORDER BY seq LIMIT 100",new String[]{peer,String.valueOf(Math.max(0,after))})){
            while(c.moveToNext()){
                JSONObject cipher=new JSONObject(c.getString(3));JSONObject payload=new JSONObject(new String(PeerCrypto.crypt(false,key,PeerCrypto.unb64(cipher.getString("iv")),"event|"+peer+"|"+c.getString(1)+"|"+c.getString(2),PeerCrypto.unb64(cipher.getString("ct"))),java.nio.charset.StandardCharsets.UTF_8));
                JSONObject row=new JSONObject().put("seq",c.getLong(0)).put("id",c.getString(1)).put("kind",c.getString(2)).put("occurred",c.getLong(4)).put("payload",payload);
                int size=PeerCrypto.utf8(row.toString()).length;if(bytes+size>300000)break;bytes+=size;rows.put(row);
            }
        }finally{java.util.Arrays.fill(key,(byte)0);}
        return new JSONObject().put("state","succeeded").put("events",rows);
    }
    public synchronized void ackEvents(String peer,JSONArray sequences)throws Exception{
        if(sequences.length()>100)throw new SecurityException("too_many_event_acks");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(int i=0;i<sequences.length();i++)db.delete("peer_events","peer=? AND seq=?",new String[]{peer,String.valueOf(sequences.getLong(i))});db.setTransactionSuccessful();}finally{db.endTransaction();}
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
    public synchronized void clearOutbound(String peer,String id){getWritableDatabase().delete("outbox","peer=? AND id=?",new String[]{peer,id});}
    public Context context(){return context;}
}
