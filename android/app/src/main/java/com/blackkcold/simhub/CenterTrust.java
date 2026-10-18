package com.blackkcold.simhub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Trusted center description: one exact HTTPS origin plus at least two independent
 * SPKI SHA-256 pins per role. This type never carries a bearer token, cookie,
 * TURN secret or private key, and it never authorizes a fallback path.
 *
 * <p>The primary source is a configuration asset injected by a controlled packaging
 * job. The secondary source is a versioned trusted QR description delivered by a
 * channel the operator already trusts. A self-signed QR arriving over an untrusted
 * network cannot prove center ownership by itself, so parsing never upgrades an
 * arbitrary value into authorization for a different origin than the one it names.
 *
 * <p>Pure JVM: no Android API, so every rejection rule is unit-testable.
 */
public final class CenterTrust {
    public static final int SCHEMA_VERSION = 1;
    public static final String ROLE_NODE = "node";
    public static final String ROLE_CONTROLLER = "controller";
    public static final int MIN_PINS = 2;
    public static final int MAX_PINS = 8;
    public static final String QR_PREFIX = "simhub-center:";
    private static final int MAX_QR_CHARS = 4096;

    /** Fixed, non-sensitive failure codes. Never include the rejected value. */
    public static final class Invalid extends SecurityException {
        public final String code;

        public Invalid(String code) {
            super(code);
            this.code = code;
        }
    }

    private final int schemaVersion;
    private final String configId;
    private final Map<String, String> origins;
    private final Map<String, List<String>> pins;

    private CenterTrust(int schemaVersion, String configId,
                        Map<String, String> origins, Map<String, List<String>> pins) {
        this.schemaVersion = schemaVersion;
        this.configId = configId;
        this.origins = origins;
        this.pins = pins;
    }

    public static CenterTrust of(String configId, String nodeOrigin, List<String> nodePins,
                                 String controllerOrigin, List<String> controllerPins) {
        Map<String, String> originMap = new LinkedHashMap<>();
        Map<String, List<String>> pinMap = new LinkedHashMap<>();
        originMap.put(ROLE_NODE, nodeOrigin);
        pinMap.put(ROLE_NODE, nodePins);
        originMap.put(ROLE_CONTROLLER, controllerOrigin);
        pinMap.put(ROLE_CONTROLLER, controllerPins);
        return new CenterTrust(SCHEMA_VERSION, configId, originMap, pinMap);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String configId() {
        return configId;
    }

    /** Canonical origin for a role, or null when the description omits that role. */
    public String origin(String role) {
        return origins.get(role);
    }

    /** Immutable pin list for a role; empty when the role is absent. */
    public List<String> pins(String role) {
        List<String> value = pins.get(role);
        return value == null ? List.of() : value;
    }

    public boolean hasRole(String role) {
        return origins.get(role) != null;
    }

    /** True only for the exact canonical origin that this description pins. */
    public boolean covers(String role, String origin) {
        String expected = origins.get(role);
        if (expected == null || origin == null) return false;
        return expected.equals(origin) && !pins(role).isEmpty();
    }

    /**
     * The signed peer QR advertises the NODE origin, never an administrator login
     * origin. Resolve the controller origin only through an independently trusted,
     * pinned two-role center description. A mismatched node hint fails closed.
     */
    public String controllerOriginForNode(String signedNodeOrigin) {
        if (!covers(ROLE_NODE, signedNodeOrigin) || !hasRole(ROLE_CONTROLLER)
                || pins(ROLE_CONTROLLER).isEmpty())
            throw new Invalid("center_pair_origin_mismatch");
        return origin(ROLE_CONTROLLER);
    }

    /**
     * Parse an injected configuration asset. Both roles are mandatory here: a
     * deployment that terminates node and controller traffic on one origin must
     * still declare the two role entries explicitly.
     */
    public static CenterTrust parse(JSONObject json) {
        if (json == null) throw new Invalid("center_config_missing");
        if (json.optInt("schemaVersion", -1) != SCHEMA_VERSION) throw new Invalid("center_config_schema");
        String configId = json.optString("configId", "");
        if (!configId.matches("[A-Za-z0-9_-]{16,64}")) throw new Invalid("center_config_id");
        Map<String, String> originMap = new LinkedHashMap<>();
        Map<String, List<String>> pinMap = new LinkedHashMap<>();
        for (String role : List.of(ROLE_NODE, ROLE_CONTROLLER)) {
            JSONObject entry = json.optJSONObject(role);
            if (entry == null) throw new Invalid("center_config_role_missing");
            originMap.put(role, requireOrigin(entry.optString("origin", "")));
            pinMap.put(role, requirePins(entry.optJSONArray("pins")));
        }
        if (originMap.get(ROLE_NODE).equals(originMap.get(ROLE_CONTROLLER))
                && !pinMap.get(ROLE_NODE).equals(pinMap.get(ROLE_CONTROLLER)))
            throw new Invalid("center_config_shared_origin_pins");
        return new CenterTrust(SCHEMA_VERSION, configId, originMap, pinMap);
    }

    public static CenterTrust fromJson(JSONObject json) {
        if (json == null) throw new Invalid("center_trust_missing");
        if (json.optInt("schemaVersion", -1) != SCHEMA_VERSION) throw new Invalid("center_trust_schema");
        String configId = json.optString("configId", "");
        if (!configId.matches("[A-Za-z0-9_-]{16,64}")) throw new Invalid("center_config_id");
        Map<String, String> originMap = new LinkedHashMap<>();
        Map<String, List<String>> pinMap = new LinkedHashMap<>();
        for (String role : List.of(ROLE_NODE, ROLE_CONTROLLER)) {
            JSONObject entry = json.optJSONObject(role);
            if (entry == null) continue;
            originMap.put(role, requireOrigin(entry.optString("origin", "")));
            pinMap.put(role, requirePins(entry.optJSONArray("pins")));
        }
        if (originMap.isEmpty()) throw new Invalid("center_trust_empty");
        String nodeOrigin = originMap.get(ROLE_NODE), controllerOrigin = originMap.get(ROLE_CONTROLLER);
        if (nodeOrigin != null && nodeOrigin.equals(controllerOrigin)
                && !pinMap.get(ROLE_NODE).equals(pinMap.get(ROLE_CONTROLLER)))
            throw new Invalid("center_config_shared_origin_pins");
        return new CenterTrust(SCHEMA_VERSION, configId, originMap, pinMap);
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("schemaVersion", schemaVersion).put("configId", configId);
            for (Map.Entry<String, String> entry : origins.entrySet()) {
                JSONArray pinArray = new JSONArray();
                for (String pin : pins(entry.getKey())) pinArray.put(pin);
                out.put(entry.getKey(), new JSONObject().put("origin", entry.getValue()).put("pins", pinArray));
            }
        } catch (Exception error) {
            throw new IllegalStateException("center_trust_encode_failed");
        }
        return out;
    }

    public String toQr() {
        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse a trusted-center QR description. One or both roles may be present;
     * an absent role stays unauthorized rather than inheriting the other role.
     */
    public static CenterTrust fromQr(String qr) {
        if (qr == null || qr.length() > MAX_QR_CHARS || !qr.startsWith(QR_PREFIX))
            throw new Invalid("center_qr_invalid");
        try {
            byte[] raw = Base64.getUrlDecoder().decode(qr.substring(QR_PREFIX.length()));
            return fromJson(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Invalid invalid) {
            throw invalid;
        } catch (Exception error) {
            throw new Invalid("center_qr_invalid");
        }
    }

    /** Canonical https origin, or null. Rejects userinfo, query, fragment and paths. */
    public static String normalizeOrigin(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) return null;
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return null;
            int port = uri.getPort();
            if (port == 0 || port > 65535) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) return null;
            return new URI("https", null, host, port, null, null, null).toASCIIString();
        } catch (Exception error) {
            return null;
        }
    }

    /** Normalize to {@code sha256/<base64>} and require a 32-byte digest. */
    public static String normalizePin(String raw) {
        if (raw == null) throw new Invalid("center_pin_invalid");
        String value = raw.trim();
        if (value.startsWith("sha256/")) value = value.substring("sha256/".length());
        if (value.isEmpty() || value.length() > 128) throw new Invalid("center_pin_invalid");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new Invalid("center_pin_invalid");
        }
        if (decoded.length != 32) throw new Invalid("center_pin_invalid");
        return "sha256/" + Base64.getEncoder().encodeToString(decoded);
    }

    /** SPKI SHA-256 pin for an encoded public key, in {@code sha256/<base64>} form. */
    public static String pinFor(byte[] encodedSubjectPublicKeyInfo) {
        try {
            return "sha256/" + Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(encodedSubjectPublicKeyInfo));
        } catch (Exception error) {
            throw new Invalid("center_pin_invalid");
        }
    }

    private static String requireOrigin(String raw) {
        String normalized = normalizeOrigin(raw);
        if (normalized == null || !normalized.equals(raw)) throw new Invalid("center_origin_invalid");
        return normalized;
    }

    private static List<String> requirePins(JSONArray array) {
        if (array == null || array.length() < MIN_PINS || array.length() > MAX_PINS)
            throw new Invalid("center_pin_count_invalid");
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) unique.add(normalizePin(array.optString(i, "")));
        if (unique.size() < MIN_PINS) throw new Invalid("center_pin_duplicate");
        return List.copyOf(new ArrayList<>(unique));
    }

    /** Never echo origins or pins: diagnostics must not leak the deployed host. */
    @Override
    public String toString() {
        return "CenterTrust{schema=" + schemaVersion + ", configId=" + configId
                + ", roles=" + origins.keySet() + ", pins=" + pins.values().stream().mapToInt(List::size).sum() + "}";
    }
}
