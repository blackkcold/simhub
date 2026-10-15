package com.blackkcold.simhub.p2p;

import org.json.JSONObject;

/** Emits the canonical cross-language signaling vector; not shipped in the app. */
public final class SignalingVectorDump {
    public static void main(String[] args) throws Exception {
        byte[] root = new byte[32];
        for (int i = 0; i < root.length; i++) root[i] = (byte) i;
        byte[] key = PeerSignalingProtocol.deriveKey(root);
        String session = "session-vector-000000000000";
        String id = "message-vector-000000000000";
        long seq = 7, expires = 1791264060L, revision = 3;
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (0xA0 + i);
        JSONObject payload = new JSONObject()
                .put("kind", "OFFER")
                .put("description", new JSONObject().put("type", "offer").put("sdp", "v=0 a=ice-ufrag:vector"));
        JSONObject frame = PeerSignalingProtocol.seal(key, session, id, "node", seq, expires, revision, nonce, payload);
        String bindingTranscript = PeerSignalingProtocol.bindingTranscript(
                PeerSignalingProtocol.MODE, "binding-vector-0000000000000", "node-vector-0000000000000000000000000000000",
                "node-key-vector", "ctrl-vector-0000000000000000000000000000000", "ctrl-key-vector",
                3, true, "challenge-vector-00000000000000", 1791264000L);
        String sessionTranscript = PeerSignalingProtocol.sessionTranscript(
                PeerSignalingProtocol.MODE, "node", "center.example.invalid", session,
                "binding-vector-0000000000000", "node-vector-0000000000000000000000000000000",
                "node-key-vector", "ctrl-vector-0000000000000000000000000000000", "ctrl-key-vector",
                3, "challenge-vector-00000000000000", 1791264060L);
        JSONObject out = new JSONObject()
                .put("version", 1)
                .put("domain", PeerSignalingProtocol.DOMAIN)
                .put("rootKeyBase64Url", PeerCrypto.b64(root))
                .put("derivedKeyBase64Url", PeerCrypto.b64(key))
                .put("session", session).put("id", id).put("direction", "node")
                .put("seq", seq).put("expires", expires).put("revision", revision)
                .put("nonceBase64Url", PeerCrypto.b64(nonce))
                .put("payload", payload)
                .put("aad", PeerSignalingProtocol.canonicalAad(frame))
                .put("ciphertextBase64Url", frame.getString("ct"))
                .put("bindingTranscriptExample", bindingTranscript)
                .put("sessionTranscriptExample", sessionTranscript);
        System.out.println(out.toString(2));
    }
}
