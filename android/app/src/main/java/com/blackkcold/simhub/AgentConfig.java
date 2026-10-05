package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;

public final class AgentConfig {
    private static final String PREF = "simhub_config";
    private static final String SECRET_DEVICE_TOKEN = "device_token";
    private static final String SECRET_VAULT_KEY = "vault_key";
    private final SharedPreferences prefs;
    private final SecretStore secrets;
    public AgentConfig(Context c) { prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE); secrets=new SecretStore(c); }
    public boolean isEnrolled() { return !server().isEmpty() && !deviceId().isEmpty() && deviceToken()!=null && vaultKey()!=null; }
    public String server() { return prefs.getString("server", ""); }
    public String deviceId() { return prefs.getString("device_id", ""); }
    public String deviceName() { return prefs.getString("device_name", "Android SIM Node"); }
    public boolean alwaysOn() { return prefs.getBoolean("always_on", false); }
    public void setAlwaysOn(boolean v) { prefs.edit().putBoolean("always_on",v).apply(); }
    public long lastHistorySync() { return prefs.getLong("last_history_sync", 0L); }
    public void setLastHistorySync(long v) { prefs.edit().putLong("last_history_sync",v).apply(); }
    public String deviceToken() { try{return secrets.getString(SECRET_DEVICE_TOKEN);}catch(Exception e){return null;} }
    public byte[] vaultKey() { try{return secrets.getBytes(SECRET_VAULT_KEY);}catch(Exception e){return null;} }
    public void setEnrollment(String server, String deviceId, String deviceName, String token, byte[] vaultKey) throws Exception {
        if(vaultKey==null||vaultKey.length!=32) throw new IllegalArgumentException("Vault key must be 32 bytes");
        prefs.edit().putString("server",server.replaceAll("/+$","")).putString("device_id",deviceId).putString("device_name",deviceName).apply();
        secrets.putString(SECRET_DEVICE_TOKEN,token); secrets.putBytes(SECRET_VAULT_KEY, Arrays.copyOf(vaultKey,vaultKey.length));
    }
    public void clearEnrollment(){ prefs.edit().remove("server").remove("device_id").remove("device_name").putBoolean("always_on",false).apply(); secrets.remove(SECRET_DEVICE_TOKEN); secrets.remove(SECRET_VAULT_KEY); }
}
