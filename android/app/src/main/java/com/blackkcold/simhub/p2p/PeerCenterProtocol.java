package com.blackkcold.simhub.p2p;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Signaling-adjacent constants and ICE configuration for the native engine.
 *
 * <p>Only approved STUN is offered. Nothing here injects a TURN credential, and a
 * signaling response can never add one: TURN stays behind its own explicit
 * authorization that this protocol does not grant.
 */
public final class PeerCenterProtocol {
    /** Authenticated capability name exchanged with the pair. */
    public static final String MODE = PeerSignalingProtocol.MODE;
    /** Public STUN endpoints already published in this repository; no credentials. */
    private static final JSONArray DEFAULT_STUN = new JSONArray()
            .put("stun:stun.l.google.com:19302").put("stun:stun.cloudflare.com:3478");

    /**
     * ICE servers for one pair. A profile may carry an explicitly approved STUN list;
     * TURN URLs are dropped rather than silently enabled.
     */
    public static JSONArray iceServers(Context context, String peerId) throws Exception {
        return iceServers(context, peerId, new JSONArray());
    }

    /**
     * TURN ICE entries come only from an authenticated, pinned center's create
     * or join response. In-band SDP/ICE cannot supply TURN endpoints.
     * Both the peer policy and this device's explicit TURN consent are checked.
     */
    public static JSONArray iceServers(Context context, String peerId, JSONArray issuedTurn) throws Exception {
        JSONArray urls = DEFAULT_STUN;
        try {
            JSONObject profile = PeerStore.get(context).peer(peerId);
            JSONArray approved = profile.optJSONArray("approvedStun");
            if (approved != null && approved.length() > 0) {
                JSONArray filtered = new JSONArray();
                for (int i = 0; i < approved.length() && filtered.length() < 4; i++) {
                    String url = approved.optString(i, "");
                    if (url.startsWith("stun:") && url.length() <= 200) filtered.put(url);
                }
                if (filtered.length() > 0) urls = filtered;
            }
        } catch (Exception ignored) {
            // An unreadable profile falls back to the published STUN defaults.
        }
        JSONArray result = new JSONArray().put(new JSONObject().put("urls", urls));
        JSONObject peer = PeerStore.get(context).peer(peerId);
        if (!ConnectionPolicy.valueOf(peer.getString("policy")).permits(
                "TURN", peer.optBoolean("fallbackConsent"),
                "ACTIVE".equals(peer.optString("relayBindingState")), peer.optBoolean("turnConsent")))
            return result;
        if (issuedTurn == null) return result;
        for (int i = 0; i < issuedTurn.length() && i < 2; i++) {
            JSONObject item = issuedTurn.optJSONObject(i);
            if (item == null) continue;
            String username = item.optString("username", "");
            String credential = item.optString("credential", "");
            long now = System.currentTimeMillis() / 1000;
            if (!TurnIceCredentialPolicy.allowsCredentials(username, credential, now)) continue;
            JSONArray supplied = item.optJSONArray("urls");
            if (supplied == null) continue;
            JSONArray allowed = new JSONArray();
            for (int j = 0; j < supplied.length() && j < 4; j++) {
                String url = supplied.optString(j, "");
                if (TurnIceCredentialPolicy.allowsUrl(url))
                    allowed.put(url);
            }
            if (allowed.length() > 0)
                result.put(new JSONObject().put("urls", allowed)
                        .put("username", username).put("credential", credential));
        }
        return result;
    }

    private PeerCenterProtocol() {
    }
}
