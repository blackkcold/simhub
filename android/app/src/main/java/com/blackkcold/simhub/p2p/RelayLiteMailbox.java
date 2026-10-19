package com.blackkcold.simhub.p2p;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * Android-hosted, bounded and durable ciphertext-only mailbox.
 *
 * This database never receives SMS plaintext or AB pair keys. Authorization is
 * checked by PeerRpc BEFORE these methods are called. Pull/ACK are scoped to
 * the authenticated destination identity, never to a user-supplied identity.
 */
public final class RelayLiteMailbox extends SQLiteOpenHelper {
    private static RelayLiteMailbox INSTANCE;
    private static final int MAX_FRAME=PeerOfflineProtocol.MAX_FRAME_BYTES;
    private static final int MAX_ROWS=2000,MAX_ROWS_PER_TARGET=500;
    private static final int MAX_BYTES=50*1024*1024,MAX_BYTES_PER_TARGET=10*1024*1024;
    private final Context context;
    public static synchronized RelayLiteMailbox get(Context c){
        if(INSTANCE==null)INSTANCE=new RelayLiteMailbox(c.getApplicationContext());
        return INSTANCE;
    }
    private RelayLiteMailbox(Context c){
        super(c,"simhub-relay-lite-v1.db",null,1);
        context=c;setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE envelopes(source TEXT NOT NULL,target TEXT NOT NULL,id TEXT NOT NULL,expires INTEGER NOT NULL,bytes INTEGER NOT NULL,cipher TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(source,target,id))");
        db.execSQL("CREATE INDEX relay_lite_target ON envelopes(target,created)");
        db.execSQL("CREATE INDEX relay_lite_expiry ON envelopes(expires)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        throw new IllegalStateException("relay_lite_upgrade_requires_migration");
    }
    public boolean enabled(){
        return context.getSharedPreferences("simhub-relay-lite",Context.MODE_PRIVATE)
            .getBoolean("enabled",false);
    }
    public void setEnabled(boolean enabled){
        context.getSharedPreferences("simhub-relay-lite",Context.MODE_PRIVATE)
            .edit().putBoolean("enabled",enabled).apply();
    }
    private static void requirePeerId(String id){
        if(id==null||!id.matches("[A-Za-z0-9_-]{16,80}"))
            throw new SecurityException("relay_lite_peer_id_invalid");
    }
    private static void purge(SQLiteDatabase db,long now){
        db.delete("envelopes","expires<=?",new String[]{String.valueOf(now)});
    }
    private static long[] usage(SQLiteDatabase db,String selection,String[] args){
        try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM envelopes "+selection,args)){
            if(!c.moveToFirst())throw new IllegalStateException("relay_lite_usage_unavailable");
            return new long[]{c.getLong(0),c.getLong(1)};
        }
    }
    /**
     * Caller identity is derived from the authenticated outer E2EE Peer RPC;
     * the inner frame's source must match this identity. Never trust a body
     * field to authenticate the depositor.
     */
    public synchronized JSONObject enqueue(String authenticatedSource,String target,JSONObject inner)throws Exception{
        if(!enabled())throw new SecurityException("relay_lite_disabled");
        requirePeerId(authenticatedSource);requirePeerId(target);
        if(authenticatedSource.equals(target))throw new SecurityException("relay_lite_self_route");
        if(inner==null||!PeerOfflineProtocol.DOMAIN.equals(inner.optString("domain"))
            ||inner.optInt("v")!=1
            ||!authenticatedSource.equals(inner.optString("from"))
            ||!target.equals(inner.optString("to")))
            throw new SecurityException("relay_lite_inner_binding_invalid");
        String id=inner.getString("id");requirePeerId(id);
        long now=System.currentTimeMillis()/1000,expires=inner.getLong("expires");
        if(expires<=now||expires>now+PeerOfflineProtocol.MAX_TTL_SECONDS)
            throw new SecurityException("relay_lite_expiry_invalid");
        String cipher=inner.toString();
        int bytes=cipher.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>MAX_FRAME||bytes<32)throw new SecurityException("relay_lite_frame_size");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            purge(db,now);
            try(Cursor existing=db.rawQuery("SELECT cipher FROM envelopes WHERE source=? AND target=? AND id=?",
                    new String[]{authenticatedSource,target,id})){
                if(existing.moveToFirst()){
                    if(!cipher.equals(existing.getString(0)))
                        throw new SecurityException("relay_lite_message_id_reused");
                    db.setTransactionSuccessful();
                    return new JSONObject().put("state","accepted").put("duplicate",true);
                }
            }
            long[] total=usage(db,"",null);
            long[] perTarget=usage(db,"WHERE target=?",new String[]{target});
            if(total[0]>=MAX_ROWS||total[1]+bytes>MAX_BYTES
                ||perTarget[0]>=MAX_ROWS_PER_TARGET||perTarget[1]+bytes>MAX_BYTES_PER_TARGET)
                throw new IllegalStateException("relay_lite_queue_full");
            ContentValues v=new ContentValues();
            v.put("source",authenticatedSource);v.put("target",target);
            v.put("id",id);v.put("expires",expires);v.put("bytes",bytes);
            v.put("cipher",cipher);v.put("created",now);
            if(db.insertOrThrow("envelopes",null,v)==-1)
                throw new IllegalStateException("relay_lite_enqueue_failed");
            db.setTransactionSuccessful();
            return new JSONObject().put("state","accepted").put("duplicate",false);
        }finally{db.endTransaction();}
    }
    /** A recipient can only pull ciphertext addressed to its authenticated ID. */
    public synchronized JSONObject pull(String authenticatedTarget,int requested)throws Exception{
        if(!enabled())throw new SecurityException("relay_lite_disabled");
        requirePeerId(authenticatedTarget);
        int limit=Math.max(1,Math.min(4,requested));
        long now=System.currentTimeMillis()/1000;
        SQLiteDatabase db=getWritableDatabase();
        JSONArray items=new JSONArray();
        db.beginTransaction();
        try{
            purge(db,now);
            try(Cursor c=db.rawQuery("SELECT source,id,cipher FROM envelopes WHERE target=? ORDER BY created ASC,id ASC LIMIT ?",
                    new String[]{authenticatedTarget,String.valueOf(limit)})){
                while(c.moveToNext())
                    items.put(new JSONObject().put("source",c.getString(0)).put("id",c.getString(1))
                        .put("frame",new JSONObject(c.getString(2))));
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new JSONObject().put("state","succeeded").put("items",items);
    }
    /** Application ACK after authenticated AEAD opening and processing only. */
    public synchronized boolean ack(String authenticatedTarget,String source,String id){
        requirePeerId(authenticatedTarget);requirePeerId(source);requirePeerId(id);
        return getWritableDatabase().delete("envelopes","target=? AND source=? AND id=?",
            new String[]{authenticatedTarget,source,id})>0;
    }
    public synchronized void revokePeer(String peer){
        requirePeerId(peer);
        getWritableDatabase().delete("envelopes","source=? OR target=?",new String[]{peer,peer});
    }
    public synchronized JSONObject stats()throws Exception{
        long now=System.currentTimeMillis()/1000;
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            purge(db,now);
            long[] totals=usage(db,"",null);
            db.setTransactionSuccessful();
            return new JSONObject().put("enabled",enabled())
                .put("queued",totals[0]).put("bytes",totals[1]);
        }finally{db.endTransaction();}
    }
}
