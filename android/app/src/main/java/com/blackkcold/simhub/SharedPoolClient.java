package com.blackkcold.simhub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opt-in pool: local events are staged under an independent Keystore-protected
 * queue key. A separate pool key encrypts transport records. No sibling Node
 * Key or plaintext SMS goes to the relay.
 */
public final class SharedPoolClient {
    private static final String PREF="simhub_pool_v1",POOL="default";
    public static final String ACTION_CACHE_UPDATED="com.blackkcold.simhub.SHARED_SMS_CACHE_UPDATED";
    private static final int PAGE=100,UPLOAD=20;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final java.util.Map<String,Store> STORES=new java.util.HashMap<>();
    private final Context context;
    private final AgentConfig config;
    private final SharedPreferences prefs;
    private final Store store;

    public SharedPoolClient(Context context){
        this.context=context.getApplicationContext();
        config=new AgentConfig(this.context);
        prefs=this.context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        synchronized(STORES){store=STORES.computeIfAbsent(this.context.getDatabasePath("simhub-pool-cache.db").getAbsolutePath(),unused->new Store(this.context));}
        synchronized(SharedPoolClient.class){
            if(prefs.getInt("publication_version",0)<2&&!prefs.edit().putInt("publication_version",2)
                .remove("initial_staged").remove("publish_cursor_date").remove("publish_cursor_id")
                .remove("publish_initial_count").remove("publish_has_more").commit())
                throw new IllegalStateException("Unable to migrate shared history progress");
        }
    }
    /** Called on both local and remote enrollment reset. Never contacts the Relay. */
    public static void clearLocalForReset(Context c){
        SharedPoolClient client=new SharedPoolClient(c);
        synchronized(SharedPoolClient.class){
            List<Integer> oldEpochs=client.epochs();
            boolean persisted=client.prefs.edit().clear().putString("privacy_generation",java.util.UUID.randomUUID().toString()).commit();
            try{client.store.clear();}finally{client.removeKeys(oldEpochs);}
            if(!persisted)throw new IllegalStateException("Unable to clear local pool settings");
        }
    }
    public boolean optedIn(){return prefs.getBoolean("requested",false);}
    public boolean approved(){return optedIn()&&prefs.getBoolean("approved",false);}
    /** A generation fences late crypto, network, history and cache work after consent changes. */
    String privacyScope(){return config.server()+"|"+config.deviceId()+"|"+config.enrollmentGeneration()+"|"+prefs.getString("privacy_generation","legacy");}
    private boolean sameScope(String expected){return config.isEnrolled()&&expected!=null&&expected.equals(privacyScope());}
    private void requireScope(String expected,boolean approval)throws java.io.IOException{
        if(!sameScope(expected)||!optedIn()||(approval&&!approved()))throw new java.io.IOException("sharing_scope_changed");
    }
    private void removeKeys(List<Integer> epochs){
        SecretStore secrets=new SecretStore(context);
        for(int epoch:epochs)try{secrets.remove("pool-key-"+epoch);}
        catch(Exception error){AppLogger.e(context,"SharedPool","Pool Key cleanup failed",error);}
    }
    /** Caller holds the same lock as stage/cache commits; no network under this lock. */
    private void invalidateLocal(boolean requested,boolean pendingDisable){
        List<Integer> oldEpochs=epochs();
        boolean persisted=prefs.edit().putString("privacy_generation",java.util.UUID.randomUUID().toString())
            .putBoolean("requested",requested).putBoolean("approved",false).putBoolean("pending_disable",pendingDisable)
            .putLong("last_download",0).putLong("oldest_download",0).remove("oldest_download_time")
            .remove("older_has_more").remove("initial_staged").remove("publish_cursor_date").remove("publish_cursor_id")
            .remove("publish_initial_count").remove("publish_has_more").remove("key_epochs").remove("current_epoch").commit();
        try{store.clear();}finally{removeKeys(oldEpochs);}
        if(!persisted)throw new IllegalStateException("Unable to persist shared-pool privacy state");
    }
    String disableLocalForPrivacy(){
        synchronized(SharedPoolClient.class){invalidateLocal(false,true);return privacyScope();}
    }
    public int pendingUploadCount(){return store.pendingCount();}
    /** A scheduled pool retry must be visible to the shared sync coordinator. */
    public boolean retryDue(){
        long next=prefs.getLong("next_retry",0L);
        return next>0&&next<=System.currentTimeMillis();
    }
    public void scheduleRetry(Exception error){
        if("sharing_scope_changed".equals(error.getMessage())||"sharing_choice_changed".equals(error.getMessage()))return;
        int attempts=Math.min(7,prefs.getInt("retry_attempts",0)+1);
        long delay=Math.min(15*60*1000L,15000L*(1L<<Math.min(5,attempts-1)));
        if(error instanceof ApiClient.ApiFailure failure && failure.status==429)
            delay=Math.max(delay,Math.max(10,failure.retryAfter)*1000L);
        if(error instanceof SecurityException)delay=Math.max(delay,5*60*1000L);
        long jitter=java.util.concurrent.ThreadLocalRandom.current().nextLong(1000,5000);
        long wait=Math.min(15*60*1000L,delay+jitter);
        prefs.edit().putInt("retry_attempts",attempts)
            .putLong("next_retry",System.currentTimeMillis()+wait).apply();
        SyncJobService.scheduleAfter(context,wait);
    }
    public String status(){
        if(!config.isEnrolled())return "未配对";
        if(!optedIn())return "未开启";
        return approved()?"已授权 · 已启用端到端加密":"等待管理员在 Web 批准";
    }
    private String path(){return "/api/v1/devices/"+config.deviceId()+"/pool";}
    private ApiClient client(){return new ApiClient(context);}

    public void setEnabled(boolean value)throws Exception{
        if(!config.isEnrolled())throw new SecurityException("Pairing required");
        if(!value){
            // Privacy-first: stop local sharing immediately, even if Relay is offline.
            final String scope;
            scope=disableLocalForPrivacy();
            try{
                client().request("POST",path()+"/request",new JSONObject().put("enabled",false));
                synchronized(SharedPoolClient.class){if(sameScope(scope))prefs.edit().putBoolean("pending_disable",false).commit();}
            }catch(Exception error){
                AppLogger.e(context,"SharedPool","Remote disable deferred until network resumes",error);
            }
            SyncJobService.scheduleNow(context);
            return;
        }
        final String scope;
        synchronized(SharedPoolClient.class){if(optedIn())return;scope=privacyScope();}
        client().request("POST",path()+"/request",new JSONObject().put("enabled",true));
        synchronized(SharedPoolClient.class){
            if(!sameScope(scope)){
                if(config.isEnrolled()&&!optedIn())prefs.edit().putBoolean("pending_disable",true).commit();
                SyncJobService.scheduleNow(context);
                throw new java.io.IOException("sharing_choice_changed");
            }
            invalidateLocal(true,false);
        }
        SyncJobService.scheduleNow(context);
    }

    /** Called after original local message was durably queued. Never performs IO over network. */
    public static boolean needsStage(Context c,String eventId){
        SharedPoolClient pool=new SharedPoolClient(c);
        return pool.optedIn()&&pool.config.isEnrolled()&&!pool.store.hasSeen(eventId);
    }
    public static boolean stage(Context c,String eventId,long occurred,String channelId,JSONObject payload){
        try{
            SharedPoolClient pool=new SharedPoolClient(c);
            final String scope;
            synchronized(SharedPoolClient.class){
                if(!pool.optedIn()||!pool.config.isEnrolled()||pool.store.hasSeen(eventId))return true;
                scope=pool.privacyScope();
            }
            return pool.stageInScope(scope,eventId,occurred,channelId,payload);
        }catch(Exception error){
            AppLogger.e(c,"SharedPool","Unable to queue protected local SMS",error);
            return false;
        }
    }
    boolean stageInScope(String scope,String eventId,long occurred,String channelId,JSONObject payload)throws Exception{
        synchronized(SharedPoolClient.class){requireScope(scope,false);if(store.hasSeen(eventId))return true;}
        JSONObject wrapped=new CryptoBox(context).encryptLocal(payload);
        synchronized(SharedPoolClient.class){requireScope(scope,false);store.stage(eventId,occurred,channelId,wrapped);}
        return true;
    }
    long historyDate(){return prefs.getLong("publish_cursor_date",0);}
    long historyId(){return prefs.getLong("publish_cursor_id",-1);}
    int initialHistoryRemaining(){return Math.max(0,PAGE-prefs.getInt("publish_initial_count",0));}
    boolean initialHistoryStaged(){return prefs.getBoolean("initial_staged",false);}
    void historyRowStaged(String scope,long date,long id,boolean initial)throws java.io.IOException{
        synchronized(SharedPoolClient.class){
        requireScope(scope,true);
        SharedPreferences.Editor edit=prefs.edit().putLong("publish_cursor_date",date).putLong("publish_cursor_id",id);
        if(initial){
            int count=prefs.getInt("publish_initial_count",0)+1;
            edit.putInt("publish_initial_count",count);
            if(count>=PAGE)edit.putBoolean("initial_staged",true);
        }
        if(!edit.commit())throw new IllegalStateException("Unable to persist shared history cursor");
        }
    }
    void historyExhausted(String scope)throws java.io.IOException{
        synchronized(SharedPoolClient.class){
        requireScope(scope,true);
        if(!prefs.edit().putBoolean("initial_staged",true).putBoolean("publish_has_more",false).commit())
            throw new IllegalStateException("Unable to persist shared history completion");
        }
    }
    public int stageOlderHistory(int requested){
        if(!approved()||!prefs.getBoolean("publish_has_more",true))return 0;
        int count=SmsHistorySync.syncSharedHistory(context,requested);
        if(count>=0)SyncJobService.scheduleNow(context);
        return count;
    }

    private List<Integer> epochs(){
        List<Integer> result=new ArrayList<>();
        String text=prefs.getString("key_epochs","");
        for(String s:text.split(","))try{if(!s.isBlank())result.add(Integer.parseInt(s));}catch(NumberFormatException ignored){}
        return result;
    }
    private void rememberEpoch(int epoch){
        List<Integer> all=epochs();if(all.contains(epoch))return;
        all.add(epoch);
        StringBuilder b=new StringBuilder();
        for(int n:all){if(b.length()>0)b.append(',');b.append(n);}
        prefs.edit().putString("key_epochs",b.toString()).apply();
    }
    private static String keyId(byte[] raw)throws Exception{return CryptoBox.keyId(raw);}
    private byte[] nodeKey()throws Exception{
        byte[] raw=config.nodeKey();
        if(raw==null||raw.length!=32){
            byte[] master=config.vaultKey();
            if(master==null||master.length!=32)throw new SecurityException("Node key unavailable");
            raw=CryptoBox.deriveDeviceKey(master,config.deviceId());
        }
        return raw;
    }
    private static byte[] decryptRaw(JSONObject envelope,byte[] key,String aad)throws Exception{
        if(!"A256GCM".equals(envelope.getString("alg")))throw new SecurityException("Unsupported envelope");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),
            new GCMParameterSpec(128,CryptoBox.ub64(envelope.getString("iv"))));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(CryptoBox.ub64(envelope.getString("ct")));
    }
    private static JSONObject encryptRaw(byte[] value,byte[] key,String kid,String aad)throws Exception{
        byte[] iv=new byte[12];RANDOM.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct=cipher.doFinal(value);
        return new JSONObject().put("v",2).put("alg","A256GCM")
            .put("kid",kid).put("iv",CryptoBox.b64(iv)).put("ct",CryptoBox.b64(ct));
    }
    private static String aad(String device,String event,long occurred,String channel,int epoch){
        return "simhub-pool-sms-v1|default|"+device+"|"+event+"|"+occurred+"|"+channel+"|"+epoch;
    }
    private byte[] poolKey(int epoch)throws Exception{
        byte[] key=new SecretStore(context).getBytes("pool-key-"+epoch);
        if(key==null||key.length!=32)throw new SecurityException("Shared pool key unavailable");
        return key;
    }

    public synchronized void sync()throws Exception{
        if(!config.isEnrolled())return;
        if(!prefs.getBoolean("pending_disable",false)&&prefs.getLong("next_retry",0)>System.currentTimeMillis())return;
        final String scope;
        synchronized(SharedPoolClient.class){scope=privacyScope();}
        if(prefs.getBoolean("pending_disable",false)){
            client().request("POST",path()+"/request",new JSONObject().put("enabled",false));
            synchronized(SharedPoolClient.class){
                if(!sameScope(scope))return;
                prefs.edit().putBoolean("pending_disable",false).commit();
            }
        }
        if(!optedIn())return;
        JSONObject status=client().request("GET",path(),null);
        synchronized(SharedPoolClient.class){
        requireScope(scope,false);
        if(!status.optBoolean("requested",false)){
            // Server-side opt-out wins; do not silently re-enable.
            invalidateLocal(false,false);return;
        }
        boolean isApproved=status.optBoolean("approved",false);
        if(!isApproved){
            // Remote revocation is authoritative: wipe other-device cache and
            // pool keys immediately. Pending initial approval must not repeatedly
            // erase the local SMS staged since the user opted in.
            if(approved()||!epochs().isEmpty())invalidateLocal(true,false);
            return;
        }
        if(!prefs.edit().putBoolean("approved",true).commit())throw new java.io.IOException("pool_approval_not_durable");
        }
        JSONArray keys=status.optJSONArray("keys");
        int current=status.optInt("epoch",0);
        if(keys==null||keys.length()==0||current<=0)return;
        SecretStore secrets=new SecretStore(context);
        synchronized(SharedPoolClient.class){
            requireScope(scope,true);
            byte[] own=nodeKey();
            try{
                for(int i=0;i<keys.length();i++){
                    JSONObject entry=keys.getJSONObject(i);
                    int epoch=entry.getInt("epoch");
                    byte[] raw=decryptRaw(entry.getJSONObject("wrappedKey"),own,
                        "simhub-pool-member-v1|default|"+config.deviceId()+"|"+epoch);
                    try{
                        if(raw.length!=32||!keyId(raw).equals(entry.getString("keyId")))throw new SecurityException("Pool Key identity mismatch");
                        secrets.putBytes("pool-key-"+epoch,raw);rememberEpoch(epoch);
                    }finally{Arrays.fill(raw,(byte)0);}
                }
                prefs.edit().putInt("current_epoch",current).commit();
            }finally{Arrays.fill(own,(byte)0);}
        }
        if(!initialHistoryStaged()){
            // Independent pool receipts and cursor also include SMS uploaded to
            // the ordinary Relay before sharing was enabled. Retry failures even
            // when the previous cycle already recorded administrator approval.
            if(SmsHistorySync.syncSharedHistory(context,PAGE)<0)
                throw new java.io.IOException("Shared SMS history staging incomplete");
        }
        // An administrative revocation freezes new ciphertext until the Vault
        // completes a new epoch. Existing historical ciphertext remains readable.
        if(!status.optBoolean("rotationRequired",false))upload(scope,current);
        download(scope);
        synchronized(SharedPoolClient.class){requireScope(scope,true);prefs.edit().remove("retry_attempts").remove("next_retry").apply();}
    }

    private void upload(String scope,int epoch)throws Exception{
        final byte[] key;
        final String device;
        synchronized(SharedPoolClient.class){requireScope(scope,true);key=poolKey(epoch);device=config.deviceId();}
        try{
        for(int page=0;page<3;page++){
            final List<Store.Pending> queued;
            synchronized(SharedPoolClient.class){requireScope(scope,true);queued=store.pending(UPLOAD);}
            if(queued.isEmpty())break;
            JSONArray batch=new JSONArray();
            for(Store.Pending p:queued){
                JSONObject payload=new CryptoBox(context).decryptLocal(p.localCipher);
                JSONObject cipher=encryptRaw(payload.toString().getBytes(StandardCharsets.UTF_8),
                    key,keyId(key),aad(device,p.id,p.occurred,p.channel,epoch));
                batch.put(new JSONObject().put("originEventId",p.id).put("occurredAt",p.occurred)
                    .put("channelId",p.channel).put("epoch",epoch).put("ciphertext",cipher));
            }
            synchronized(SharedPoolClient.class){requireScope(scope,true);}
            JSONObject response=client().request("POST",path()+"/messages",new JSONObject().put("messages",batch));
            JSONArray results=response.getJSONArray("results");
            if(results.length()!=queued.size())throw new IllegalStateException("Incomplete upload acknowledgement");
            for(int i=0;i<results.length();i++){
                JSONObject ack=results.getJSONObject(i);
                if(!ack.optBoolean("accepted")||!queued.get(i).id.equals(ack.getString("originEventId")))
                    throw new SecurityException("Pool upload acknowledgement mismatch");
            }
            synchronized(SharedPoolClient.class){
                requireScope(scope,true);
                for(Store.Pending pending:queued)store.markUploaded(pending.id);
            }
        }
        }finally{Arrays.fill(key,(byte)0);}
        if(store.pendingCount()>0)SyncJobService.scheduleAfter(context,5000);
    }

    private void download(String scope)throws Exception{
        synchronized(SharedPoolClient.class){requireScope(scope,true);}
        long cursor=prefs.getLong("last_download",0);
        String query=cursor>0?"?since="+cursor+"&limit="+PAGE:"?limit="+PAGE;
        JSONObject response=client().request("GET",path()+"/messages"+query,null);
        int count=acceptDownloaded(scope,response,cursor,false);
        if(count>0)context.sendBroadcast(new Intent(ACTION_CACHE_UPDATED).setPackage(context.getPackageName()));
        if(response.optBoolean("hasMore")&&cursor>0)SyncJobService.scheduleAfter(context,10000);
    }

    /** Network-independent commit boundary, shared by normal and older-history reads. */
    int acceptDownloaded(String scope,JSONObject response,long cursor,boolean older)throws Exception{
        synchronized(SharedPoolClient.class){
        requireScope(scope,true);
        JSONArray messages=response.optJSONArray("messages");
        if(messages==null)return 0;
        if(messages.length()>PAGE)throw new SecurityException("Shared history page exceeds limit");
        long max=cursor;
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);
            int epoch=m.getInt("epoch");
            // Reject unverifiable events before caching, and never expose ciphertext as plaintext.
            byte[] key=poolKey(epoch);
            try{
                byte[] plaintext=decryptRaw(m.getJSONObject("ciphertext"),key,
                    aad(m.getString("deviceId"),m.getString("originEventId"),m.getLong("occurredAt"),m.optString("channelId",""),epoch));
                try{new JSONObject(new String(plaintext,StandardCharsets.UTF_8));}finally{Arrays.fill(plaintext,(byte)0);}
            }finally{Arrays.fill(key,(byte)0);}
            store.cache(m);
            long seq=m.getLong("seq");max=Math.max(max,seq);
        }
        if((older||cursor==0)&&messages.length()>0){
            JSONObject oldest=messages.getJSONObject(messages.length()-1);
            prefs.edit().putLong("oldest_download",oldest.getLong("seq"))
                .putLong("oldest_download_time",oldest.getLong("occurredAt"))
                .putBoolean("older_has_more",response.optBoolean("hasMore",false)).apply();
        }
        if(older)prefs.edit().putBoolean("older_has_more",response.optBoolean("hasMore",false)).apply();
        else if(max>cursor)prefs.edit().putLong("last_download",max).apply();
        store.trim(2000);
        return messages.length();
        }
    }

    public synchronized int loadOlder()throws Exception{
        if(!approved())return 0;
        final String scope;
        synchronized(SharedPoolClient.class){scope=privacyScope();requireScope(scope,true);}
        long before=prefs.getLong("oldest_download",0),time=prefs.getLong("oldest_download_time",0);
        if(before<=0||time<=0||!prefs.getBoolean("older_has_more",false))return 0;
        JSONObject response=client().request("GET",path()+"/messages?beforeTime="+time+"&beforeSeq="+before+"&limit=50",null);
        return acceptDownloaded(scope,response,before,true);
    }

    /** Only decrypted in process memory for UI; server and SQLite see ciphertext. */
    public List<JSONObject> cached(int limit){
        List<JSONObject> result=new ArrayList<>();
        final String scope;
        final List<JSONObject> rows;
        synchronized(SharedPoolClient.class){
            if(!approved()||!config.isEnrolled())return result;
            scope=privacyScope();rows=store.cached(Math.max(1,Math.min(2000,limit)));
        }
        for(JSONObject m:rows)try{
            int epoch=m.getInt("epoch");
            byte[] key=poolKey(epoch);
            try{
                byte[] bytes=decryptRaw(m.getJSONObject("ciphertext"),key,
                    aad(m.getString("deviceId"),m.getString("originEventId"),m.getLong("occurredAt"),m.optString("channelId",""),epoch));
                try{m.put("payload",new JSONObject(new String(bytes,StandardCharsets.UTF_8)));result.add(m);}
                finally{Arrays.fill(bytes,(byte)0);}
            }finally{Arrays.fill(key,(byte)0);}
        }catch(Exception e){AppLogger.e(context,"SharedPool","Unable to decode cached message",e);}
        synchronized(SharedPoolClient.class){return sameScope(scope)&&approved()?result:new ArrayList<>();}
    }

    private static final class Store extends SQLiteOpenHelper {
        Store(Context c){super(c,"simhub-pool-cache.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE staged(id TEXT PRIMARY KEY,occurred INTEGER NOT NULL,channel TEXT NOT NULL,local_cipher TEXT NOT NULL,created_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE uploaded(id TEXT PRIMARY KEY,at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE cached(seq INTEGER PRIMARY KEY,device_id TEXT NOT NULL,event_id TEXT NOT NULL,occurred INTEGER NOT NULL,channel TEXT NOT NULL,epoch INTEGER NOT NULL,ciphertext TEXT NOT NULL)");
            db.execSQL("CREATE INDEX idx_pool_cache_time ON cached(occurred DESC,seq DESC)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
        synchronized boolean hasSeen(String id){
            try(Cursor row=getReadableDatabase().rawQuery("SELECT 1 FROM uploaded WHERE id=? UNION ALL SELECT 1 FROM staged WHERE id=? LIMIT 1",new String[]{id,id})){
                return row.moveToFirst();
            }
        }
        synchronized void stage(String id,long occurred,String channel,JSONObject cipher){
            SQLiteDatabase db=getWritableDatabase();
            try(Cursor old=db.rawQuery("SELECT 1 FROM uploaded WHERE id=?",new String[]{id})){
                if(old.moveToFirst())return;
            }
            db.execSQL("INSERT OR IGNORE INTO staged VALUES(?,?,?,?,?)",
                new Object[]{id,occurred,channel,cipher.toString(),System.currentTimeMillis()/1000});
        }
        static final class Pending {
            final String id,channel;final long occurred;final JSONObject localCipher;
            Pending(String id,String channel,long occurred,JSONObject cipher){
                this.id=id;this.channel=channel;this.occurred=occurred;localCipher=cipher;
            }
        }
        synchronized int pendingCount(){
            try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM staged",null)){
                return c.moveToFirst()?c.getInt(0):0;
            }
        }
        synchronized List<Pending> pending(int limit)throws Exception{
            List<Pending> out=new ArrayList<>();
            try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,occurred,channel,local_cipher FROM staged ORDER BY created_at ASC LIMIT ?",
                new String[]{String.valueOf(limit)})){
                while(c.moveToNext())out.add(new Pending(c.getString(0),c.getString(2),c.getLong(1),new JSONObject(c.getString(3))));
            }
            return out;
        }
        synchronized void markUploaded(String id){
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            try{
                db.execSQL("INSERT OR REPLACE INTO uploaded(id,at) VALUES(?,?)",
                    new Object[]{id,System.currentTimeMillis()/1000});
                db.delete("staged","id=?",new String[]{id});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        synchronized void cache(JSONObject m)throws Exception{
            getWritableDatabase().execSQL("INSERT OR REPLACE INTO cached VALUES(?,?,?,?,?,?,?)",
                new Object[]{m.getLong("seq"),m.getString("deviceId"),m.getString("originEventId"),
                    m.getLong("occurredAt"),m.optString("channelId",""),m.getInt("epoch"),m.getJSONObject("ciphertext").toString()});
        }
        synchronized List<JSONObject> cached(int limit){
            List<JSONObject> rows=new ArrayList<>();
            try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT seq,device_id,event_id,occurred,channel,epoch,ciphertext FROM cached ORDER BY occurred DESC,seq DESC LIMIT ?",
                new String[]{String.valueOf(limit)})){
                while(c.moveToNext())try{
                    rows.add(new JSONObject().put("seq",c.getLong(0)).put("deviceId",c.getString(1))
                        .put("originEventId",c.getString(2)).put("occurredAt",c.getLong(3))
                        .put("channelId",c.getString(4)).put("epoch",c.getInt(5))
                        .put("ciphertext",new JSONObject(c.getString(6))));
                }catch(Exception ignored){}
            }
            return rows;
        }
        synchronized void trim(int count){
            getWritableDatabase().execSQL(
                "DELETE FROM cached WHERE seq NOT IN (SELECT seq FROM cached ORDER BY occurred DESC,seq DESC LIMIT ?)",
                new Object[]{count});
            getWritableDatabase().execSQL(
                "DELETE FROM uploaded WHERE id NOT IN (SELECT id FROM uploaded ORDER BY at DESC LIMIT 10000)");
        }
        synchronized void clear(){
            SQLiteDatabase db=getWritableDatabase();
            db.delete("cached",null,null);db.delete("staged",null,null);
            db.delete("uploaded",null,null);
        }
    }
}
