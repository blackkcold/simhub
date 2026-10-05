package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
    private static final String ALIAS = "simhub-local-secrets-v1";
    private static final String PREF = "simhub_secret_store";
    private final SharedPreferences prefs;
    public SecretStore(Context c) { prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return kg.generateKey();
    }
    public void putBytes(String name, byte[] value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(value); String packed = b64(c.getIV()) + "." + b64(ct);
        prefs.edit().putString(name, packed).apply();
    }
    public byte[] getBytes(String name) throws Exception {
        String packed = prefs.getString(name, null); if (packed == null) return null;
        String[] p = packed.split("\\.", 2); if (p.length != 2) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, ub64(p[0])));
        return c.doFinal(ub64(p[1]));
    }
    public void putString(String name, String value) throws Exception { putBytes(name, value.getBytes(StandardCharsets.UTF_8)); }
    public String getString(String name) throws Exception { byte[] b = getBytes(name); return b == null ? null : new String(b, StandardCharsets.UTF_8); }
    public void remove(String name) { prefs.edit().remove(name).apply(); }
    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static byte[] ub64(String s) { return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
}
