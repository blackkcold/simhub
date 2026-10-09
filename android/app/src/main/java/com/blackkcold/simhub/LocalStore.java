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
    private LocalStore(Context c){super(c,"simhub-agent.db",null,5);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,kind TEXT NOT NULL,occurred_at INTEGER NOT NULL,subscription_id TEXT NOT NULL,has_otp INTEGER NOT NULL,metadata_json TEXT NOT NULL,ciphertext_json TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE processed_commands(id TEXT PRIMARY KEY,state TEXT NOT NULL DEFAULT 'succeeded',command_type TEXT NOT NULL DEFAULT '',processed_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE command_acks(command_id TEXT PRIMARY KEY,state TEXT NOT NULL,result_json TEXT NOT NULL DEFAULT '{}',updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE pending_sms(command_id TEXT PRIMARY KEY,provider_uri TEXT NOT NULL,total_parts INTEGER NOT NULL,sent_parts INTEGER NOT NULL DEFAULT 0,delivered_parts INTEGER NOT NULL DEFAULT 0,failed INTEGER NOT NULL DEFAULT 0,event_cipher_json TEXT NOT NULL,sub_id INTEGER NOT NULL,created_at INTEGER NOT NULL)");
        createPartTable(db);
        createUploadIndex(db);
    }

    private static void createUploadIndex(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS uploaded_event_ids(id TEXT PRIMARY KEY,uploaded_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_uploaded_event_age ON uploaded_event_ids(uploaded_at)");
    }

    private static void createPartTable(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_part_status(command_id TEXT NOT NULL,part_index INTEGER NOT NULL,sent_state INTEGER NOT NULL DEFAULT 0,delivery_state INTEGER NOT NULL DEFAULT 0,sent_result_code INTEGER,delivery_result_code INTEGER,updated_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(command_id,part_index))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_part_command ON sms_part_status(command_id,part_index)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2){
            try{db.execSQL("ALTER TABLE processed_commands ADD COLUMN state TEXT NOT NULL DEFAULT 'succeeded'");}catch(Exception ignored){}
            db.execSQL("CREATE TABLE IF NOT EXISTS command_acks(command_id TEXT PRIMARY KEY,state TEXT NOT NULL,result_json TEXT NOT NULL DEFAULT '{}',updated_at INTEGER NOT NULL)");
        }
        if(oldV<3)createPartTable(db);
        if(oldV<4)db.execSQL("ALTER TABLE processed_commands ADD COLUMN command_type TEXT NOT NULL DEFAULT ''");
        if(oldV<5)createUploadIndex(db);
    }

    public synchronized boolean queueEvent(String id,String kind,long occurredAt,String subId,boolean hasOtp,JSONObject metadata,JSONObject cipher){
        // Retain durable upload receipts so explicit rescans do not reupload
        // already-delivered provider SMS; unsent events remain separately queued.
        try(Cursor seen=getReadableDatabase().query("uploaded_event_ids",new String[]{"id"},"id=?",new String[]{id},null,null,null)){
            if(seen.moveToFirst())return true;
        }
        ContentValues v=new ContentValues();v.put("id",id);v.put("kind",kind);v.put("occurred_at",occurredAt);v.put("subscription_id",subId==null?"":subId);v.put("has_otp",hasOtp?1:0);v.put("metadata_json",metadata==null?"{}":metadata.toString());v.put("ciphertext_json",cipher.toString());v.put("created_at",System.currentTimeMillis()/1000);
        long x=getWritableDatabase().insertWithOnConflict("events",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if(x!=-1)return true;
        try(Cursor c=getReadableDatabase().query("events",new String[]{"id"},"id=?",new String[]{id},null,null,null)){return c.moveToFirst();}
    }

    public synchronized List<JSONObject> pendingEvents(int limit)throws Exception{
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("events",null,null,null,null,null,"CASE WHEN kind='sms.received' THEN 0 WHEN kind='sms.history' THEN 2 ELSE 1 END ASC, created_at ASC",String.valueOf(limit))){
            while(c.moveToNext())out.add(new JSONObject().put("eventId",c.getString(c.getColumnIndexOrThrow("id"))).put("kind",c.getString(c.getColumnIndexOrThrow("kind"))).put("occurredAt",c.getLong(c.getColumnIndexOrThrow("occurred_at"))).put("subscriptionId",c.getString(c.getColumnIndexOrThrow("subscription_id"))).put("hasOtp",c.getInt(c.getColumnIndexOrThrow("has_otp"))==1).put("metadata",new JSONObject(c.getString(c.getColumnIndexOrThrow("metadata_json")))).put("ciphertext",new JSONObject(c.getString(c.getColumnIndexOrThrow("ciphertext_json")))));
        }
        return out;
    }

    public synchronized void markEventSent(String id){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("id",id);v.put("uploaded_at",System.currentTimeMillis()/1000);
            db.insertWithOnConflict("uploaded_event_ids",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            db.delete("events","id=?",new String[]{id});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized int uploadedEventCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM uploaded_event_ids",null)){return c.moveToFirst()?c.getInt(0):0;}}
    public synchronized int pendingEventCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events",null)){return c.moveToFirst()?c.getInt(0):0;}}

    public synchronized boolean claimCommand(String id,String commandType){
        ContentValues v=new ContentValues();v.put("id",id);v.put("state","claimed");v.put("command_type",commandType);v.put("processed_at",System.currentTimeMillis()/1000);
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
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("command_id",commandId);v.put("provider_uri",providerUri);v.put("total_parts",Math.max(1,totalParts));v.put("event_cipher_json",eventCipher.toString());v.put("sub_id",subId);v.put("created_at",System.currentTimeMillis()/1000);
            db.insertWithOnConflict("pending_sms",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("sms_part_status","command_id=?",new String[]{commandId});
            for(int i=0;i<Math.max(1,totalParts);i++){
                ContentValues p=new ContentValues();p.put("command_id",commandId);p.put("part_index",i);p.put("updated_at",System.currentTimeMillis()/1000);
                db.insertWithOnConflict("sms_part_status",null,p,SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public static final class PendingStatus {
        public final boolean exists,complete,failed,delivered,deliveryFailed;
        public final String providerUri;
        public final JSONObject eventCipher;
        public final int subId,totalParts;
        PendingStatus(boolean e,boolean c,boolean f,boolean d,boolean df,String u,JSONObject x,int s,int t){exists=e;complete=c;failed=f;delivered=d;deliveryFailed=df;providerUri=u;eventCipher=x;subId=s;totalParts=t;}
    }

    private void ensurePartRows(SQLiteDatabase db,String id,int total){
        for(int i=0;i<Math.max(1,total);i++){
            ContentValues p=new ContentValues();p.put("command_id",id);p.put("part_index",i);p.put("updated_at",System.currentTimeMillis()/1000);
            db.insertWithOnConflict("sms_part_status",null,p,SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    private PendingStatus status(String id)throws Exception{
        SQLiteDatabase db=getReadableDatabase();
        String providerUri;JSONObject eventCipher;int subId,total;
        try(Cursor c=db.query("pending_sms",null,"command_id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())return new PendingStatus(false,false,false,false,false,"",null,-1,0);
            providerUri=c.getString(c.getColumnIndexOrThrow("provider_uri"));
            eventCipher=new JSONObject(c.getString(c.getColumnIndexOrThrow("event_cipher_json")));
            subId=c.getInt(c.getColumnIndexOrThrow("sub_id"));
            total=Math.max(1,c.getInt(c.getColumnIndexOrThrow("total_parts")));
        }
        ensurePartRows(getWritableDatabase(),id,total);
        int sentOk=0,sentFail=0,deliveryOk=0,deliveryFail=0;
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN sent_state=1 THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN sent_state=2 THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN delivery_state=1 THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN delivery_state=2 THEN 1 ELSE 0 END),0) FROM sms_part_status WHERE command_id=?",new String[]{id})){
            if(c.moveToFirst()){sentOk=c.getInt(0);sentFail=c.getInt(1);deliveryOk=c.getInt(2);deliveryFail=c.getInt(3);}
        }
        boolean sendFailed=sentFail>0;
        boolean deliveryFailed=deliveryFail>0;
        boolean sentComplete=(sentOk+sentFail)>=total;
        boolean delivered=deliveryOk>=total&&!sendFailed&&!deliveryFailed;
        return new PendingStatus(true,sentComplete||sendFailed,sendFailed||deliveryFailed,delivered,deliveryFailed,providerUri,eventCipher,subId,total);
    }

    public synchronized PendingStatus recordSentPart(String id,int partIndex,boolean ok,int resultCode)throws Exception{
        PendingStatus current=status(id);if(!current.exists)return current;
        if(partIndex<0||partIndex>=current.totalParts)return current;
        ContentValues v=new ContentValues();v.put("sent_state",ok?1:2);v.put("sent_result_code",resultCode);v.put("updated_at",System.currentTimeMillis()/1000);
        getWritableDatabase().update("sms_part_status",v,"command_id=? AND part_index=?",new String[]{id,String.valueOf(partIndex)});
        return status(id);
    }

    public synchronized PendingStatus recordDeliveredPart(String id,int partIndex,boolean ok,int resultCode)throws Exception{
        PendingStatus current=status(id);if(!current.exists)return current;
        if(partIndex<0||partIndex>=current.totalParts)return current;
        ContentValues v=new ContentValues();v.put("delivery_state",ok?1:2);v.put("delivery_result_code",resultCode);v.put("updated_at",System.currentTimeMillis()/1000);
        getWritableDatabase().update("sms_part_status",v,"command_id=? AND part_index=?",new String[]{id,String.valueOf(partIndex)});
        return status(id);
    }

    public synchronized void removePendingSms(String id){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{db.delete("sms_part_status","command_id=?",new String[]{id});db.delete("pending_sms","command_id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized List<String> expireStalePendingSms(long ageSeconds){
        long cutoff=System.currentTimeMillis()/1000-Math.max(3600,ageSeconds);List<String> stale=new ArrayList<>(),uncertain=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("pending_sms",new String[]{"command_id"},"created_at<?",new String[]{String.valueOf(cutoff)},null,null,null)){while(c.moveToNext())stale.add(c.getString(0));}
        for(String id:stale){
            try{
                PendingStatus st=status(id);
                if(!st.complete||st.failed)uncertain.add(id);
            }catch(Exception ignored){uncertain.add(id);}
            removePendingSms(id);
        }
        return uncertain;
    }

    public synchronized int pendingSmsCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending_sms",null)){return c.moveToFirst()?c.getInt(0):0;}}
    public synchronized void recoverStaleClaims(){
        long cutoff=System.currentTimeMillis()/1000-120;
        SQLiteDatabase db=getWritableDatabase();
        List<String> uncertain=new ArrayList<>();
        try(Cursor cur=db.rawQuery("SELECT id FROM processed_commands WHERE state='claimed' AND command_type='sms.send' AND processed_at<? AND id NOT IN (SELECT command_id FROM pending_sms)",new String[]{String.valueOf(cutoff)})){
            while(cur.moveToNext())uncertain.add(cur.getString(0));
        }
        for(String id:uncertain){
            // The process may have died after dispatching a radio side effect.
            // Never retry this SMS automatically; report the outcome as unknown.
            finishCommand(id,"submitted");
            JSONObject reason=new JSONObject();
            try{reason.put("reason","interrupted_send_unknown");}catch(Exception ignored){}
            queueCommandAck(id,"submitted",reason);
        }
        db.execSQL("DELETE FROM processed_commands WHERE state='claimed' AND command_type!='sms.send' AND processed_at<? AND id NOT IN (SELECT command_id FROM pending_sms)",new Object[]{cutoff});
    }

    public synchronized void resetForReenrollment(){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            db.delete("events",null,null);db.delete("uploaded_event_ids",null,null);db.delete("processed_commands",null,null);db.delete("command_acks",null,null);db.delete("sms_part_status",null,null);db.delete("pending_sms",null,null);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
