package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolves the trusted center description for this installation.
 *
 * <p>Priority: a configuration asset injected by a controlled packaging job, then a
 * versioned trusted QR description saved by the user. There is no silent
 * trust-on-first-use migration: an already enrolled origin without a pin description
 * keeps working on the platform trust chain but is never treated as pinned, and the
 * new center-assisted path stays blocked until the user supplies a trusted
 * description.
 */
public final class CenterTrustStore {
    private static final String PREFS = "simhub_center_trust";
    private static final String KEY_DESCRIPTION = "description";
    private static final String ASSET_NAME = "center-config.json";
    private static final int MAX_ASSET_BYTES = 64 * 1024;
    private static volatile CenterTrustStore INSTANCE;

    private final Context app;
    private volatile CenterTrust injected;
    private volatile boolean injectedLoaded;
    private volatile CenterTrust stored;
    private volatile boolean storedLoaded;
    private volatile String lastError = "";

    private CenterTrustStore(Context context) {
        this.app = context.getApplicationContext();
    }

    public static CenterTrustStore get(Context context) {
        if (INSTANCE == null) synchronized (CenterTrustStore.class) {
            if (INSTANCE == null) INSTANCE = new CenterTrustStore(context);
        }
        return INSTANCE;
    }

    /**
     * The injected configuration, or null when this build carries none or the asset
     * is invalid. Invalid input is rejected, never partially trusted.
     */
    public CenterTrust injected() {
        if (!injectedLoaded) synchronized (this) {
            if (!injectedLoaded) {
                injected = loadInjected();
                injectedLoaded = true;
            }
        }
        return injected;
    }

    /** The user-supplied trusted description, or null. Parsed once per store. */
    public CenterTrust stored() {
        if (!storedLoaded) synchronized (this) {
            if (!storedLoaded) {
                stored = loadStored();
                storedLoaded = true;
            }
        }
        return stored;
    }

    private CenterTrust loadStored() {
        String raw = prefs().getString(KEY_DESCRIPTION, null);
        if (raw == null) return null;
        try {
            return CenterTrust.fromJson(new JSONObject(raw));
        } catch (Exception error) {
            lastError = error instanceof CenterTrust.Invalid invalid ? invalid.code : "center_trust_invalid";
            return null;
        }
    }

    /** Injected configuration wins; a stored description is the fallback. */
    public CenterTrust effective() {
        CenterTrust configured = injected();
        return configured != null ? configured : stored();
    }

    /** Persist a trusted description that the user obtained out of band. */
    public void save(CenterTrust trust) {
        if (trust == null) throw new CenterTrust.Invalid("center_trust_missing");
        prefs().edit().putString(KEY_DESCRIPTION, trust.toJson().toString()).apply();
        synchronized (this) {
            stored = trust;
            storedLoaded = true;
        }
        lastError = "";
    }

    public void clear() {
        prefs().edit().remove(KEY_DESCRIPTION).apply();
        synchronized (this) {
            stored = null;
            storedLoaded = true;
        }
    }

    public boolean configured() {
        return effective() != null;
    }

    /** Fixed, non-sensitive reason for the most recent rejection. */
    public String lastError() {
        return lastError;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private CenterTrust loadInjected() {
        try (InputStream stream = app.getAssets().open(ASSET_NAME)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > MAX_ASSET_BYTES - out.size()) throw new CenterTrust.Invalid("center_config_too_large");
                out.write(buffer, 0, count);
            }
            return CenterTrust.parse(new JSONObject(out.toString(StandardCharsets.UTF_8)));
        } catch (java.io.FileNotFoundException absent) {
            return null;
        } catch (Exception error) {
            lastError = error instanceof CenterTrust.Invalid invalid ? invalid.code : "center_config_invalid";
            return null;
        }
    }
}
