package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Arrays;

public final class AgentConfig {
    private static final String PREF="simhub_config";
    private static final String SECRET_DEVICE_TOKEN="device_token";
    private static final String SECRET_PENDING_DEVICE_TOKEN="pending_device_token";
    private static final String SECRET_VAULT_KEY="vault_key";
    private static final String SECRET_NODE_KEY="node_key";
    private static final String SECRET_LOCAL_QUEUE_KEY="local_queue_key";
    private final SharedPreferences prefs;
    private final SecretStore secrets;

    public AgentConfig(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);secrets=new SecretStore(c);}
    public boolean isEnrolled(){return !resetPending()&&!server().isEmpty()&&!deviceId().isEmpty()&&deviceToken()!=null&&(nodeKey()!=null||vaultKey()!=null);}
    public boolean resetPending(){return prefs.getBoolean("reset_pending",false);}
    public boolean remoteResetNotified(){return prefs.getBoolean("remote_reset_notified",false);}
    public void markRemoteResetNotified(){prefs.edit().putBoolean("remote_reset_notified",true).apply();}
    public boolean remoteResetPending(){return prefs.getBoolean("remote_reset_pending",false);}
    public void markRemoteResetPending(){markResetPending();prefs.edit().putBoolean("remote_reset_pending",true).apply();}
    public void markResetPending(){prefs.edit().putBoolean("reset_pending",true).apply();resetSyncBackoff();}
    public String server(){return prefs.getString("server","");}
    public String deviceId(){return prefs.getString("device_id","");}
    public String deviceName(){return prefs.getString("device_name","Android SIM Node");}
    public boolean alwaysOn(){return prefs.getBoolean("always_on",false);}
    public void setAlwaysOn(boolean v){prefs.edit().putBoolean("always_on",v).apply();}
    public boolean dataFallbackEnabled(){return prefs.getBoolean("data_fallback_enabled",false);}
    public String dataFallbackChannel(){return prefs.getString("data_fallback_channel","");}
    public long dataFallbackRevision(){return prefs.getLong("data_fallback_revision",0);}
    public void setDataFallback(boolean enabled,String channel,long revision){
        prefs.edit().putBoolean("data_fallback_enabled",enabled)
                .putString("data_fallback_channel",enabled?channel:"")
                .putLong("data_fallback_revision",enabled?revision:0).apply();
    }
    public long nextSyncAllowedAt(){return prefs.getLong("next_sync_allowed_at",0L);}
    public void setNextSyncAllowedAt(long at){prefs.edit().putLong("next_sync_allowed_at",at).apply();}
    public synchronized int incrementSyncBackoff(){
        int failures=Math.min(9,prefs.getInt("sync_backoff_failures",0)+1);
        prefs.edit().putInt("sync_backoff_failures",failures).apply();
        return failures;
    }
    public int syncBackoffFailures(){return prefs.getInt("sync_backoff_failures",0);}
    public void resetSyncBackoff(){prefs.edit().remove("sync_backoff_failures").remove("next_sync_allowed_at").remove("sync_backoff_failures").apply();}
    public long historyDate(){
        if(prefs.contains("history_cursor_date")) return prefs.getLong("history_cursor_date",0L);
        long legacy=prefs.getLong("last_history_sync",0L);
        return legacy>0?Math.max(0,legacy-1):0L;
    }
    public long historyId(){return prefs.getLong("history_cursor_id",-1L);}
    public boolean historyInitialized(){return prefs.getBoolean("history_initialized_v2",false);}
    public void setHistoryInitialized(boolean value){prefs.edit().putBoolean("history_initialized_v2",value).apply();}
    public long historyBackfillDate(){return prefs.getLong("history_backfill_date",0L);}
    public long historyBackfillId(){return prefs.getLong("history_backfill_id",-1L);}
    public void setHistoryBackfillCursor(long date,long id){prefs.edit().putLong("history_backfill_date",date).putLong("history_backfill_id",id).apply();}

    public void setHistoryCursor(long date,long id){prefs.edit().putLong("history_cursor_date",date).putLong("history_cursor_id",id).apply();}
    public void recordQueueFailure(){prefs.edit().putLong("queue_failures",prefs.getLong("queue_failures",0)+1).putLong("last_queue_failure_at",System.currentTimeMillis()/1000).apply();}
    public long queueFailures(){return prefs.getLong("queue_failures",0);}
    public long lastQueueFailureAt(){return prefs.getLong("last_queue_failure_at",0);}
    public void recordSmsReceived(long at){prefs.edit().putLong("last_sms_received_at",at).apply();}
    public void recordSmsProviderError(String reason){prefs.edit().putString("sms_provider_error",reason==null?"unknown":reason.substring(0,Math.min(80,reason.length()))).apply();}
    public void clearSmsProviderError(){prefs.edit().remove("sms_provider_error").apply();}
    public String smsProviderError(){return prefs.getString("sms_provider_error","");}
    public void recordSyncSuccess(){prefs.edit().putLong("last_sync_success_at",System.currentTimeMillis()/1000).remove("last_sync_error").apply();}
    public void recordSyncError(String reason){prefs.edit().putString("last_sync_error",reason==null?"unknown":reason.substring(0,Math.min(80,reason.length()))).apply();}
    public long lastSmsReceivedAt(){return prefs.getLong("last_sms_received_at",0);}
    public long lastSyncSuccessAt(){return prefs.getLong("last_sync_success_at",0);}
    public String lastSyncError(){return prefs.getString("last_sync_error","");}
    public void clearQueueFailures(){prefs.edit().remove("queue_failures").remove("last_queue_failure_at").apply();}
    public String deviceToken(){try{return secrets.getString(SECRET_DEVICE_TOKEN);}catch(Exception e){return null;}}
    public long tokenIssuedAt(){return prefs.getLong("token_issued_at",0);}
    public boolean tokenRotationPending(){return prefs.getBoolean("token_rotation_pending",false);}
    public String pendingDeviceToken(){try{return secrets.getString(SECRET_PENDING_DEVICE_TOKEN);}catch(Exception e){return null;}}
    public long pendingTokenExpiresAt(){return prefs.getLong("pending_token_expires_at",0);}
    public void stageDeviceToken(String token,long expiresAt)throws Exception{
        secrets.putString(SECRET_PENDING_DEVICE_TOKEN,token);
        prefs.edit().putBoolean("token_rotation_pending",true).putLong("pending_token_expires_at",expiresAt).apply();
    }
    public void discardPendingDeviceToken(){
        secrets.remove(SECRET_PENDING_DEVICE_TOKEN);
        prefs.edit().putBoolean("token_rotation_pending",false).remove("pending_token_expires_at").apply();
    }
    public void commitDeviceToken(long issuedAt)throws Exception{
        String pending=pendingDeviceToken();
        if(pending==null||pending.isBlank())throw new IllegalStateException("Pending device token missing");
        secrets.putString(SECRET_DEVICE_TOKEN,pending);
        secrets.remove(SECRET_PENDING_DEVICE_TOKEN);
        prefs.edit().putLong("token_issued_at",issuedAt).putBoolean("token_rotation_pending",false).remove("pending_token_expires_at").apply();
    }
    public byte[] vaultKey(){try{return secrets.getBytes(SECRET_VAULT_KEY);}catch(Exception e){return null;}}
    public byte[] nodeKey(){try{return secrets.getBytes(SECRET_NODE_KEY);}catch(Exception e){return null;}}
    public String nodeKeyId(){return prefs.getString("node_key_id","");}
    public boolean hasIndependentNodeKey(){byte[] k=nodeKey();return k!=null&&k.length==32&&!nodeKeyId().isBlank();}

    public synchronized byte[] localQueueKey(){
        try{
            byte[] existing=secrets.getBytes(SECRET_LOCAL_QUEUE_KEY);
            if(existing!=null&&existing.length==32)return existing;
            byte[] key=new byte[32];new SecureRandom().nextBytes(key);secrets.putBytes(SECRET_LOCAL_QUEUE_KEY,key);return key;
        }catch(Exception e){throw new IllegalStateException("Unable to load local queue key",e);}
    }

    private void setBaseEnrollment(String server,String deviceId,String deviceName,String token)throws Exception{
        prefs.edit().remove("remote_reset_notified").putString("server",server.replaceAll("/+$","")).putString("device_id",deviceId).putString("device_name",deviceName).putLong("token_issued_at",System.currentTimeMillis()/1000).putBoolean("token_rotation_pending",false).remove("last_history_sync").remove("history_cursor_date").remove("history_cursor_id").remove("history_initialized_v2").remove("history_backfill_date").remove("history_backfill_id").remove("next_sync_allowed_at").apply();
        secrets.putString(SECRET_DEVICE_TOKEN,token);
        localQueueKey();
    }

    public void setLegacyEnrollment(String server,String deviceId,String deviceName,String token,byte[] vaultKey)throws Exception{
        if(vaultKey==null||vaultKey.length!=32)throw new IllegalArgumentException("Vault key must be 32 bytes");
        setBaseEnrollment(server,deviceId,deviceName,token);
        secrets.putBytes(SECRET_VAULT_KEY,Arrays.copyOf(vaultKey,vaultKey.length));
        secrets.remove(SECRET_NODE_KEY);prefs.edit().remove("node_key_id").apply();
    }

    public void setNodeEnrollment(String server,String deviceId,String deviceName,String token,String keyId,byte[] nodeKey)throws Exception{
        if(nodeKey==null||nodeKey.length!=32||keyId==null||keyId.isBlank())throw new IllegalArgumentException("Node key is invalid");
        setBaseEnrollment(server,deviceId,deviceName,token);
        secrets.putBytes(SECRET_NODE_KEY,Arrays.copyOf(nodeKey,nodeKey.length));
        prefs.edit().putString("node_key_id",keyId).apply();
        secrets.remove(SECRET_VAULT_KEY);
    }

    public void rotateNodeKey(String keyId,byte[] nodeKey)throws Exception{
        if(nodeKey==null||nodeKey.length!=32||keyId==null||keyId.isBlank())throw new IllegalArgumentException("Node key is invalid");
        secrets.putBytes(SECRET_NODE_KEY,Arrays.copyOf(nodeKey,nodeKey.length));
        prefs.edit().putString("node_key_id",keyId).apply();
        secrets.remove(SECRET_VAULT_KEY);
    }

    public void clearEnrollment(){
        prefs.edit().remove("server").remove("device_id").remove("device_name").remove("last_history_sync").remove("history_cursor_date").remove("history_cursor_id").remove("queue_failures").remove("last_queue_failure_at").remove("sms_provider_error").remove("node_key_id").remove("token_issued_at").remove("token_rotation_pending").remove("pending_token_expires_at").remove("reset_pending").remove("remote_reset_pending").putBoolean("always_on",false).apply();
        secrets.remove(SECRET_DEVICE_TOKEN);secrets.remove(SECRET_PENDING_DEVICE_TOKEN);secrets.remove(SECRET_VAULT_KEY);secrets.remove(SECRET_NODE_KEY);secrets.remove(SECRET_LOCAL_QUEUE_KEY);
    }
}
