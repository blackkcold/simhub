package com.blackkcold.simhub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class LocalStore extends SQLiteOpenHelper {
    private static LocalStore INSTANCE;
    public static synchronized LocalStore get(Context c){if(INSTANCE==null)INSTANCE=new LocalStore(c.getApplicationContext());return INSTANCE;}
    private LocalStore(Context c){super(c,"simhub-agent.db",null,2);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,kind TEXT NOT NULL,occurred_at INTEGER NOT NULL,subscription_id TEXT NOT NULL,has_otp INTEGER NOT NULL,metadata_json TEXT NOT NULL,ciphertext_json TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE processed_commands(id TEXT PRIMARY KEY,state TEXT NOT NULL DEFAULT 'succeeded',processed_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE command_acks(command_id TEXT PRIMARY KEY,state TEXT NOT NULL,result_json TEXT NOT NULL DEFAULT '{}',updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE pending_sms(command_id TEXT PRIMARY KEY,provider_uri TEXT NOT NULL,total_parts INTEGER NOT NULL,sent_parts INTEGER NOT NULL DEFAULT 0,delivered_parts INTEGER NOT NULL DEFAULT 0,failed INTEGER NOT NULL DEFAULT 0,event_cipher_json TEXT NOT NULL,sub_id INTEGER NOT NULL,created_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2){
            try{db.execSQL("ALTER TABLE processed_commands ADD COLUMN state TEXT NOT NULL DEFAULT 'succeeded'");}catch(Exception ignored){}
            db.execSQL("CREATE TABLE IF NOT EXISTS command_acks(command_id TEXT PRIMARY KEY,state TEXT NOT NULL,result_json TEXT NOT NULL DEFAULT '{}',updated_at INTEGER NOT NULL)");
        }
    }

    public synchronized boolean queueEvent(String id,String kind,long occurredAt,String subId,boolean hasOtp,JSONObject metadata,JSONObject cipher){
        ContentValues v=new ContentValues();v.put("id",id);v.put("kind",kind);v.put("occurred_at",occurredAt);v.put("subscription_id",subId==null?"":subId);v.put("has_otp",hasOtp?1:0);v.put("metadata_json",metadata==null?"{}":metadata.toString());v.put("ciphertext_json",cipher.toString());v.put("created_at",System.currentTimeMillis()/1000);
        long x=getWritableDatabase().insertWithOnConflict("events",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if(x!=-1)return true;
        try(Cursor c=getReadableDatabase().query("events",new String[]{"id"},"id=?",new String[]{id},null,null,null)){return c.moveToFirst();}
    }

    public synchronized List<JSONObject> pendingEvents(int limit)throws Exception{
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("events",null,null,null,null,null,"created_at ASC",String.valueOf(limit))){
            while(c.moveToNext())out.add(new JSONObject().put("eventId",c.getString(c.getColumnIndexOrThrow("id"))).put("kind",c.getString(c.getColumnIndexOrThrow("kind"))).put("occurredAt",c.getLong(c.getColumnIndexOrThrow("occurred_at"))).put("subscriptionId",c.getString(c.getColumnIndexOrThrow("subscription_id"))).put("hasOtp",c.getInt(c.getColumnIndexOrThrow("has_otp"))==1).put("metadata",new JSONObject(c.getString(c.getColumnIndexOrThrow("metadata_json")))).put("ciphertext",new JSONObject(c.getString(c.getColumnIndexOrThrow("ciphertext_json")))));
        }
        return out;
    }

    public synchronized void markEventSent(String id){getWritableDatabase().delete("events","id=?",new String[]{id});}
    public synchronized int pendingEventCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events",null)){return c.moveToFirst()?c.getInt(0):0;}}

    public synchronized boolean claimCommand(String id){
        ContentValues v=new ContentValues();v.put("id",id);v.put("state","claimed");v.put("processed_at",System.currentTimeMillis()/1000);
        return getWritableDatabase().insertWithOnConflict("processed_commands",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    public synchronized String commandState(String id){
        try(Cursor c=getReadableDatabase().query("processed_commands",new String[]{"state"},"id=?",new String[]{id},null,null,null)){return c.moveToFirst()?c.getString(0):null;}
    }
    public synchronized void finishCommand(String id,String state){
        ContentValues v=new ContentValues();v.put("state",state);v.put("processed_at",System.currentTimeMillis()/1000);
        getWritableDatabase().update("processed_commands",v,"id=?",new String[]{id});
        getWritableDatabase().execSQL("DELETE FROM processed_commands WHERE processed_at < ?",new Object[]{System.currentTimeMillis()/1000-30L*86400});
    }
    public synchronized void queueCommandAck(String id,String state,JSONObject result){
        ContentValues v=new ContentValues();v.put("command_id",id);v.put("state",state);v.put("result_json",result==null?"{}":result.toString());v.put("updated_at",System.currentTimeMillis()/1000);
        getWritableDatabase().insertWithOnConflict("command_acks",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized List<JSONObject> pendingCommandAcks(int limit)throws Exception{
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("command_acks",null,null,null,null,null,"updated_at ASC",String.valueOf(limit))){
            while(c.moveToNext())out.add(new JSONObject().put("commandId",c.getString(c.getColumnIndexOrThrow("command_id"))).put("state",c.getString(c.getColumnIndexOrThrow("state"))).put("result",new JSONObject(c.getString(c.getColumnIndexOrThrow("result_json")))));
        }
        return out;
    }
    public synchronized void markCommandAckSent(String id,String state){getWritableDatabase().delete("command_acks","command_id=? AND state=?",new String[]{id,state});}

    public synchronized void createPendingSms(String commandId,String providerUri,int totalParts,JSONObject eventCipher,int subId){
        ContentValues v=new ContentValues();v.put("command_id",commandId);v.put("provider_uri",providerUri);v.put("total_parts",totalParts);v.put("event_cipher_json",eventCipher.toString());v.put("sub_id",subId);v.put("created_at",System.currentTimeMillis()/1000);
        getWritableDatabase().insertWithOnConflict("pending_sms",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static final class PendingStatus {
        public final boolean exists,complete,failed,delivered;
        public final String providerUri;
        public final JSONObject eventCipher;
        public final int subId;
        PendingStatus(boolean e,boolean c,boolean f,boolean d,String u,JSONObject x,int s){exists=e;complete=c;failed=f;delivered=d;providerUri=u;eventCipher=x;subId=s;}
    }
    private PendingStatus status(String id)throws Exception{
        try(Cursor c=getReadableDatabase().query("pending_sms",null,"command_id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())return new PendingStatus(false,false,false,false,"",null,-1);
            int total=c.getInt(c.getColumnIndexOrThrow("total_parts")),sent=c.getInt(c.getColumnIndexOrThrow("sent_parts")),del=c.getInt(c.getColumnIndexOrThrow("delivered_parts"));
            boolean failed=c.getInt(c.getColumnIndexOrThrow("failed"))==1;
            return new PendingStatus(true,sent>=total||failed,failed,del>=total&&!failed,c.getString(c.getColumnIndexOrThrow("provider_uri")),new JSONObject(c.getString(c.getColumnIndexOrThrow("event_cipher_json"))),c.getInt(c.getColumnIndexOrThrow("sub_id")));
        }
    }
    public synchronized PendingStatus recordSentPart(String id,boolean ok)throws Exception{if(ok)getWritableDatabase().execSQL("UPDATE pending_sms SET sent_parts=sent_parts+1 WHERE command_id=?",new Object[]{id});else getWritableDatabase().execSQL("UPDATE pending_sms SET failed=1 WHERE command_id=?",new Object[]{id});return status(id);}
    public synchronized PendingStatus recordDeliveredPart(String id)throws Exception{getWritableDatabase().execSQL("UPDATE pending_sms SET delivered_parts=delivered_parts+1 WHERE command_id=?",new Object[]{id});return status(id);}
    public synchronized void removePendingSms(String id){getWritableDatabase().delete("pending_sms","command_id=?",new String[]{id});}

    public synchronized void resetForReenrollment(){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            db.delete("events",null,null);db.delete("processed_commands",null,null);db.delete("command_acks",null,null);db.delete("pending_sms",null,null);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
