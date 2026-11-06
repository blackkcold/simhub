package com.blackkcold.simhub.p2p;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.blackkcold.simhub.SecretStore;
import org.json.JSONObject;
import java.util.Arrays;

/** Pair-generation-bound encrypted payloads with bounded idempotency metadata. */
public final class RelayLiteCache extends SQLiteOpenHelper {
    static final int MAX_ROWS=4096,MAX_PEER_ROWS=1024;
    static final int MAX_BYTES=12*1024*1024,MAX_PEER_BYTES=3*1024*1024,MAX_VALUE_BYTES=96*1024;
    private static final String DOMAIN="simhub-relay-lite-cache-v1";
    private static RelayLiteCache instance;
    private final Context context;
    public static synchronized RelayLiteCache get(Context c){
        if(instance==null)instance=new RelayLiteCache(c.getApplicationContext());return instance;
    }
    private RelayLiteCache(Context c){super(c,"simhub-relay-lite-cache-v1.db",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE entries(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL,created INTEGER NOT NULL,protected INTEGER NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,cipher TEXT,state TEXT NOT NULL,PRIMARY KEY(peer,generation,id,kind))");
        db.execSQL("CREATE INDEX lite_cache_expiry ON entries(expires)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO metadata(name,value) VALUES('legacyRetireAt',?)",new Object[]{System.currentTimeMillis()/1000+PeerOfflineProtocol.MAX_TTL_SECONDS});
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("unsupported_cache_upgrade");}
    static String generation(byte[] root)throws Exception{
        byte[] binding=PeerCrypto.derive(root,PeerCrypto.utf8(DOMAIN),"pair-generation");
        try{return PeerCrypto.digest(binding);}finally{Arrays.fill(binding,(byte)0);}
    }
    private static String[] identity(String peer,byte[] root,String id,String kind)throws Exception{
        if(!("sent".equals(kind)||"response".equals(kind)))throw new IllegalArgumentException("cache_kind");
        return new String[]{peer,generation(root),id,kind};
    }
    private static String aad(String[] identity,long expires){return DOMAIN+"|"+String.join("|",identity)+"|"+expires;}
    private static JSONObject crypt(boolean encrypt,byte[] root,String[] identity,long expires,JSONObject value)throws Exception{
        byte[] key=PeerCrypto.derive(root,PeerCrypto.utf8(DOMAIN),"local-payload");
        try{
            if(encrypt){
                byte[] nonce=PeerCrypto.random(12);
                return new JSONObject().put("iv",PeerCrypto.b64(nonce)).put("ct",PeerCrypto.b64(PeerCrypto.crypt(true,key,nonce,aad(identity,expires),PeerCrypto.utf8(value.toString()))));
            }
            return new JSONObject(new String(PeerCrypto.crypt(false,key,PeerCrypto.unb64(value.getString("iv")),aad(identity,expires),PeerCrypto.unb64(value.getString("ct"))),java.nio.charset.StandardCharsets.UTF_8));
        }finally{Arrays.fill(key,(byte)0);}
    }
    public static final class Entry {
        public final JSONObject value;
        public final String state;
        Entry(JSONObject value,String state){this.value=value;this.state=state;}
    }
    private long legacyRetireAt(SQLiteDatabase db){
        try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE name='legacyRetireAt'",null)){return c.moveToFirst()?c.getLong(0):0;}
    }
    private void purge(SQLiteDatabase db,long now){
        db.delete("entries","expires<=?",new String[]{String.valueOf(now)});
        long deadline=legacyRetireAt(db);
        if(deadline>0&&deadline<=now){
            removeLegacy(null);
            db.execSQL("UPDATE metadata SET value=0 WHERE name='legacyRetireAt'");
        }
    }
    /** Old preferences are read-only during one maximum offline TTL, then erased.
     * This avoids forgetting in-flight response identities on upgrade. New traffic
     * never adds preference entries; revocation erases a peer's legacy values now.
     */
    public synchronized JSONObject legacy(String peer,String id,String kind,long now)throws Exception{
        SQLiteDatabase db=getWritableDatabase();purge(db,now);
        if(legacyRetireAt(db)<=now)return null;
        String value=new SecretStore(context).getString("p2p.relayLite."+kind+"."+peer+"."+id);
        if(value==null)return null;
        JSONObject payload=new JSONObject(value);
        if("sent".equals(kind)&&payload.optLong("expires")<=now)return null;
        return payload;
    }
    private void removeLegacy(String peer){
        SharedPreferences preferences=context.getSharedPreferences("simhub_secret_store",Context.MODE_PRIVATE);
        SharedPreferences.Editor edit=preferences.edit();boolean changed=false;
        for(String name:preferences.getAll().keySet()){
            for(String kind:new String[]{"sent","response"}){
                String prefix="p2p.relayLite."+kind+"."+(peer==null?"":peer+".");
                if(name.startsWith(prefix)){edit.remove(name);changed=true;break;}
            }
        }
        if(changed&&!edit.commit())throw new IllegalStateException("legacy_cache_erase_failed");
    }
    private Entry read(SQLiteDatabase db,byte[] root,String[] key,String digest)throws Exception{
        try(Cursor row=db.rawQuery("SELECT digest,expires,cipher,state FROM entries WHERE peer=? AND generation=? AND id=? AND kind=?",key)){
            if(!row.moveToFirst())return null;
            if(digest!=null&&!row.getString(0).isEmpty()&&!digest.equals(row.getString(0)))
                throw new SecurityException("relay_lite_cache_id_reused");
            return new Entry(row.isNull(2)?null:crypt(false,root,key,row.getLong(1),new JSONObject(row.getString(2))),row.getString(3));
        }
    }
    public synchronized Entry get(String peer,byte[] root,String id,String kind,long now)throws Exception{
        SQLiteDatabase db=getWritableDatabase();purge(db,now);return read(db,root,identity(peer,root,id,kind),null);
    }
    private static long total(SQLiteDatabase db,String expression,String peer){
        try(Cursor row=db.rawQuery("SELECT "+expression+" FROM entries"+(peer==null?"":" WHERE peer=?"),peer==null?null:new String[]{peer})){
            row.moveToFirst();return row.getLong(0);
        }
    }
    private static boolean discardRead(SQLiteDatabase db,String peer){
        try(Cursor row=db.rawQuery("SELECT rowid FROM entries WHERE protected=0"+(peer==null?"":" AND peer=?")+" ORDER BY created,rowid LIMIT 1",peer==null?null:new String[]{peer})){
            if(!row.moveToFirst())return false;
            return db.delete("entries","rowid=?",new String[]{row.getString(0)})==1;
        }
    }
    /** A durable claim precedes dispatch. Protected claims cannot be evicted early. */
    public synchronized Entry claim(String peer,byte[] root,String id,String kind,String digest,long expires,boolean protect,long now)throws Exception{
        if(expires<=now||expires>now+PeerOfflineProtocol.MAX_TTL_SECONDS)throw new SecurityException("cache_expiry_invalid");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            purge(db,now);String[] key=identity(peer,root,id,kind);Entry previous=read(db,root,key,digest);
            if(previous!=null){db.setTransactionSuccessful();return previous;}
            while(total(db,"COUNT(*)",peer)>=MAX_PEER_ROWS)if(!discardRead(db,peer))throw new IllegalStateException("relay_lite_peer_cache_full");
            while(total(db,"COUNT(*)",null)>=MAX_ROWS)if(!discardRead(db,null))throw new IllegalStateException("relay_lite_cache_full");
            ContentValues row=new ContentValues();row.put("peer",key[0]);row.put("generation",key[1]);row.put("id",id);row.put("kind",kind);
            row.put("digest",digest);row.put("expires",expires);row.put("created",now);row.put("protected",protect?1:0);row.put("state","claimed");
            db.insertOrThrow("entries",null,row);db.setTransactionSuccessful();return null;
        }finally{db.endTransaction();}
    }
    private static void evictPayload(SQLiteDatabase db,String peer,long ownRow){
        try(Cursor row=db.rawQuery("SELECT rowid FROM entries WHERE cipher IS NOT NULL AND rowid<>?"+(peer==null?"":" AND peer=?")+" ORDER BY created,rowid LIMIT 1",
                peer==null?new String[]{String.valueOf(ownRow)}:new String[]{String.valueOf(ownRow),peer})){
            if(!row.moveToFirst())throw new IllegalStateException("relay_lite_cache_payload_full");
            db.execSQL("UPDATE entries SET cipher=NULL,bytes=0,state='evicted' WHERE rowid=?",new Object[]{row.getLong(0)});
        }
    }
    public synchronized void complete(String peer,byte[] root,String id,String kind,JSONObject value)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            String[] key=identity(peer,root,id,kind);long expires,ownRow,oldBytes;
            try(Cursor row=db.rawQuery("SELECT expires,rowid,bytes FROM entries WHERE peer=? AND generation=? AND id=? AND kind=?",key)){
                if(!row.moveToFirst())throw new IllegalStateException("relay_lite_cache_claim_missing");
                expires=row.getLong(0);ownRow=row.getLong(1);oldBytes=row.getLong(2);
            }
            String cipher=crypt(true,root,key,expires,value).toString();int bytes=PeerCrypto.utf8(cipher).length;
            if(bytes>MAX_VALUE_BYTES)throw new SecurityException("relay_lite_cache_value_too_large");
            while(total(db,"COALESCE(SUM(bytes),0)",peer)-oldBytes+bytes>MAX_PEER_BYTES)evictPayload(db,peer,ownRow);
            while(total(db,"COALESCE(SUM(bytes),0)",null)-oldBytes+bytes>MAX_BYTES)evictPayload(db,null,ownRow);
            ContentValues update=new ContentValues();update.put("cipher",cipher);update.put("bytes",bytes);update.put("state","complete");
            if(db.update("entries",update,"rowid=?",new String[]{String.valueOf(ownRow)})!=1)throw new IllegalStateException("relay_lite_cache_write_lost");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void revokePeer(String peer){
        getWritableDatabase().delete("entries","peer=?",new String[]{peer});removeLegacy(peer);
    }
    public synchronized JSONObject stats(long now)throws Exception{
        SQLiteDatabase db=getWritableDatabase();purge(db,now);
        return new JSONObject().put("rows",total(db,"COUNT(*)",null)).put("bytes",total(db,"COALESCE(SUM(bytes),0)",null));
    }
}
