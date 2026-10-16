package com.blackkcold.simhub.p2p;

import org.json.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Purpose-separated peer signaling envelope.
 *
 * The business wire format is PeerProtocol with DOMAIN "simhub-peer-v1"; this
 * protocol derives its own subkey with HKDF-SHA256 under the fixed label
 * "simhub-peer-signal-v1", uses its own domain, its own AAD and its own strict
 * parser. A business ciphertext can never open here and vice versa. The outer
 * carries only delivery metadata (all of it authenticated as AAD); the
 * plaintext admits only OFFER, ANSWER, ICE, END_OF_CANDIDATES and CLOSE.
 */
public final class PeerSignalingProtocol {
    public static final String DOMAIN = "simhub-peer-signal-v1";
    public static final String MODE = "center-signaling-v1";
    public static final int MAX_FRAME = 65536;
    public static final long MAX_LIFETIME = 120;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{16,80}");
    private static final Set<String> KINDS = Set.of("OFFER", "ANSWER", "ICE", "END_OF_CANDIDATES", "CLOSE");
    private static final Set<String> FIELDS = Set.of("v", "domain", "session", "id", "dir", "seq", "expires",
            "revision", "nonce", "ct");

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    /** HKDF-SHA256(root, salt=DOMAIN, info=DOMAIN+"|aead", L=32). */
    public static byte[] deriveKey(byte[] root) throws Exception {
        if (root == null || root.length != 32) throw new SecurityException("signal_root_key_invalid");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(utf8(DOMAIN), "HmacSHA256"));
        byte[] prk = mac.doFinal(root);
        try {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(utf8(DOMAIN + "|aead"));
            mac.update((byte) 1);
            return mac.doFinal();
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /** Length-prefixed encoding. All authenticated values are ASCII, so the byte count equals the length. */
    static String lp(String value) { return value.length() + ":" + value; }

    static String canonicalAad(JSONObject frame) throws Exception {
        return DOMAIN + "|" + lp(Integer.toString(frame.getInt("v"))) + lp(frame.getString("domain"))
                + lp(frame.getString("session")) + lp(frame.getString("id")) + lp(frame.getString("dir"))
                + lp(Long.toString(frame.getLong("seq"))) + lp(Long.toString(frame.getLong("expires")))
                + lp(Long.toString(frame.getLong("revision"))) + lp(frame.getString("nonce"));
    }

    public static JSONObject seal(byte[] key, String session, String id, String direction, long seq,
                                  long expires, long revision, JSONObject payload) throws Exception {
        return seal(key, session, id, direction, seq, expires, revision, PeerCrypto.random(12), payload);
    }

    static JSONObject seal(byte[] key, String session, String id, String direction, long seq,
                           long expires, long revision, byte[] nonce, JSONObject payload) throws Exception {
        requireIdentity(session);
        requireIdentity(id);
        if (!"node".equals(direction) && !"controller".equals(direction))
            throw new SecurityException("signal_direction_invalid");
        if (seq < 1 || revision < 1) throw new SecurityException("signal_bounds_invalid");
        if (nonce == null || nonce.length != 12) throw new SecurityException("signal_nonce_invalid");
        JSONObject frame = new JSONObject().put("v", 1).put("domain", DOMAIN).put("session", session)
                .put("id", id).put("dir", direction).put("seq", seq).put("expires", expires)
                .put("revision", revision).put("nonce", PeerCrypto.b64(nonce));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(utf8(canonicalAad(frame)));
        frame.put("ct", PeerCrypto.b64(cipher.doFinal(utf8(payload.toString()))));
        if (frame.toString().length() > MAX_FRAME) throw new SecurityException("signal_frame_too_large");
        return frame;
    }

    public static JSONObject open(byte[] key, String expectSession, String senderDirection, JSONObject frame,
                                  long now) throws Exception {
        if (frame == null || frame.toString().length() > MAX_FRAME || !FIELDS.equals(fields(frame)))
            throw new SecurityException("signal_frame_rejected");
        if (frame.getInt("v") != 1 || !DOMAIN.equals(frame.getString("domain"))
                || !expectSession.equals(frame.getString("session")) || !senderDirection.equals(frame.getString("dir")))
            throw new SecurityException("signal_domain_mismatch");
        requireIdentity(frame.getString("id"));
        long seq = frame.getLong("seq"), expires = frame.getLong("expires"), revision = frame.getLong("revision");
        if (seq < 1 || revision < 1) throw new SecurityException("signal_bounds_invalid");
        if (expires < now || expires > now + MAX_LIFETIME) throw new SecurityException("signal_frame_expired");
        byte[] nonce = PeerCrypto.unb64(frame.getString("nonce"));
        byte[] ciphertext = PeerCrypto.unb64(frame.getString("ct"));
        if (nonce.length != 12 || ciphertext.length < 16) throw new SecurityException("signal_cipher_invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(utf8(canonicalAad(frame)));
        JSONObject payload = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        requirePayload(payload);
        return payload;
    }

    /** Only signaling content is admitted; RPC actions and business fields never pass. */
    private static void requirePayload(JSONObject payload) throws Exception {
        String kind = payload.optString("kind", "");
        if (!KINDS.contains(kind)) throw new SecurityException("signal_content_rejected");
        if ("OFFER".equals(kind) || "ANSWER".equals(kind)) {
            if (!fields(payload).equals(Set.of("kind", "description")))
                throw new SecurityException("signal_content_rejected");
            JSONObject description = payload.getJSONObject("description");
            if (!Set.of("type", "sdp").containsAll(fields(description))
                    || !kind.toLowerCase(Locale.ROOT).equals(description.optString("type")))
                throw new SecurityException("signal_content_rejected");
            String sdp = description.optString("sdp", "");
            if (sdp.isEmpty() || sdp.length() > 120000) throw new SecurityException("signal_content_rejected");
        } else if ("ICE".equals(kind)) {
            if (!fields(payload).equals(Set.of("kind", "candidate")))
                throw new SecurityException("signal_content_rejected");
            JSONObject candidate = payload.getJSONObject("candidate");
            if (!fields(candidate).equals(Set.of("candidate", "sdpMid", "sdpMLineIndex")))
                throw new SecurityException("signal_content_rejected");
            String value = candidate.optString("candidate", "");
            if (value.isEmpty() || value.length() > 512) throw new SecurityException("signal_content_rejected");
        } else if (!fields(payload).equals(Set.of("kind"))) {
            throw new SecurityException("signal_content_rejected");
        }
    }

    /** Android's org.json exposes keys(), not keySet(); keep the strict set comparison. */
    static Set<String> fields(JSONObject value) {
        Set<String> names = new LinkedHashSet<>();
        for (Iterator<String> keys = value.keys(); keys.hasNext(); ) names.add(keys.next());
        return names;
    }

    /** Maps a decrypted signaling payload onto the native transport's signal() input. */
    public static JSONObject transportSignal(JSONObject payload) {
        try {
            String kind = payload.optString("kind", "");
            if ("OFFER".equals(kind) || "ANSWER".equals(kind))
                return new JSONObject().put("description", payload.optJSONObject("description"));
            if ("ICE".equals(kind)) return new JSONObject().put("candidate", payload.optJSONObject("candidate"));
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    // -- purpose-labelled signing transcripts --------------------------------

    /** Must match server/peer_signaling.py::binding_transcript byte for byte. */
    public static String bindingTranscript(String protocolVersion, String bindingId, String nodeId,
                                           String nodePublicKey, String controllerPeerId,
                                           String controllerPublicKey, int revision, boolean allowed,
                                           String challengeId, long expires) {
        return DOMAIN + "|binding|" + lp(protocolVersion) + lp(bindingId) + lp(nodeId) + lp(nodePublicKey)
                + lp(controllerPeerId) + lp(controllerPublicKey) + lp(Integer.toString(revision))
                + lp(allowed ? "1" : "0") + lp(challengeId) + lp(Long.toString(expires));
    }

    /** Must match server/peer_signaling.py::session_transcript byte for byte. */
    public static String sessionTranscript(String protocolVersion, String role, String centerId,
                                           String sessionId, String bindingId, String nodeId,
                                           String nodePublicKey, String controllerPeerId,
                                           String controllerPublicKey, int revision, String challengeId,
                                           long expires) {
        return DOMAIN + "|session|" + lp(protocolVersion) + lp(role) + lp(centerId) + lp(sessionId)
                + lp(bindingId) + lp(nodeId) + lp(nodePublicKey) + lp(controllerPeerId)
                + lp(controllerPublicKey) + lp(Integer.toString(revision)) + lp(challengeId)
                + lp(Long.toString(expires));
    }

    /** Explicit signaling-purpose signing entry; never reuses the business prefix. */
    public static String signTranscript(java.security.PrivateKey key, String transcript) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(utf8(transcript));
        return PeerCrypto.b64(signer.sign());
    }

    public static void verifyTranscript(String publicKey, String transcript, String signature) throws Exception {
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(PeerCrypto.publicKey(publicKey));
        verifier.update(utf8(transcript));
        if (!verifier.verify(PeerCrypto.unb64(signature)))
            throw new SecurityException("signal_signature_rejected");
    }

    private static void requireIdentity(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new SecurityException("signal_identifier_invalid");
    }

    private PeerSignalingProtocol() { }
}
