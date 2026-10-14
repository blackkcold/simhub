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
    private PeerStore(Context c){super(c,"simhub-peers-v1.db",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY,profile TEXT NOT NULL)");
        db.execSQL("CREATE TABLE receipts(peer TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,result TEXT,state TEXT NOT NULL,PRIMARY KEY(peer,id))");
        db.execSQL("CREATE TABLE outbox(peer TEXT NOT NULL,id TEXT NOT NULL,cipher TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(peer,id))");
    }
    public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("unsupported_peer_database_version");}
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
        new SecretStore(context).putBytes("p2p.content."+id,key);
        ContentValues v=new ContentValues();v.put("id",id);v.put("profile",p.toString());getWritableDatabase().insertOrThrow("peers",null,v);
    }
    public synchronized JSONObject peer(String id)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT profile FROM peers WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("peer_not_authorized");return new JSONObject(c.getString(0));}}
    public synchronized JSONArray list(){JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT profile FROM peers ORDER BY id",null)){while(c.moveToNext())try{a.put(new JSONObject(c.getString(0)));}catch(Exception e){throw new IllegalStateException(e);}}return a;}
    public synchronized void update(JSONObject p)throws Exception{ContentValues v=new ContentValues();v.put("profile",p.toString());if(getWritableDatabase().update("peers",v,"id=?",new String[]{p.getString("id")})!=1)throw new SecurityException("peer_not_authorized");}
    public byte[] key(String id)throws Exception{JSONObject p=peer(id);if("REVOKED".equals(p.optString("pairState")))throw new SecurityException("peer_revoked");byte[] key=new SecretStore(context).getBytes("p2p.content."+id);if(key==null||key.length!=32)throw new SecurityException("peer_key_unavailable");return key;}
    public synchronized void revoke(String id)throws Exception{JSONObject p=peer(id);p.put("pairState","REVOKED").put("activeTransport","OFFLINE");update(p);new SecretStore(context).remove("p2p.content."+id);getWritableDatabase().delete("outbox","peer=?",new String[]{id});}
    /** Atomic durable claim. A crash after dispatch remains 'indeterminate', never blindly resends. */
    public synchronized JSONObject claim(String peer,String id,String digest)throws Exception{
        ContentValues v=new ContentValues();v.put("peer",peer);v.put("id",id);v.put("digest",digest);v.put("state","claimed");
        if(getWritableDatabase().insertWithOnConflict("receipts",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT digest,result FROM receipts WHERE peer=? AND id=?",new String[]{peer,id})){
            if(!c.moveToFirst()||!digest.equals(c.getString(0)))throw new SecurityException("peer_request_id_reused");
            return c.isNull(1)?new JSONObject().put("state","indeterminate").put("reason","previous_dispatch_unconfirmed"):new JSONObject(c.getString(1));
        }
    }
    public synchronized void finish(String peer,String id,JSONObject result)throws Exception{ContentValues v=new ContentValues();v.put("result",result.toString());v.put("state",result.optString("state","succeeded"));if(getWritableDatabase().update("receipts",v,"peer=? AND id=?",new String[]{peer,id})!=1)throw new IllegalStateException("peer_receipt_missing");}
    public Context context(){return context;}
}
