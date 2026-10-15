package com.blackkcold.simhub.p2p;

import android.content.Context;

import com.blackkcold.simhub.ApiClient;
import com.blackkcold.simhub.CenterTrust;
import com.blackkcold.simhub.CenterTrustStore;
import com.blackkcold.simhub.ControllerSecureClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Center-auxiliary connection for one pair.
 *
 * <p>Both roles keep their existing credentials: the node uses its Device token, the
 * controller uses its admin session plus a one-time session token. They create a
 * short-lived, pair-scoped session on the already registered center and exchange only
 * end-to-end encrypted SDP/ICE frames. The center never sees the pair content key and
 * never carries business data; the native data channel still applies the existing
 * PeerProtocol grants.
 *
 * <p>Nothing here authorizes a route. The retired public-discovery carrier is not a
 * fallback: without explicit signaling consent and a trusted description this
 * connector reports a fixed status and stops.
 */
public final class PeerCenterConnector implements HubTransport, AutoCloseable {
    private static final Map<String, PeerCenterConnector> ACTIVE = new ConcurrentHashMap<>();
    private static final ExecutorService CLOSER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "simhub-peer-signal-close");
        thread.setDaemon(true);
        return thread;
    });
    private static final String NODE = "node";
    private static final String CONTROLLER = "controller";
    private static final String MODE = PeerSignalingProtocol.MODE;
    private static final long BINDING_TTL = 7L * 86400;
    private static final long SESSION_MAX = 280;
    private static final long POLL_INTERVAL_MS = 1500;
    private static final long OFFER_INTERVAL_MS = 15000;
    private static final int MAX_OUTGOING = 64;

    /** Role-specific transport of opaque signaling frames. */
    public interface Carrier {
        JSONObject challenge(String bindingId) throws Exception;
        JSONObject bind(JSONObject body) throws Exception;
        JSONObject createSession(JSONObject body) throws Exception;
        JSONObject listSessions() throws Exception;
        JSONObject join(String sessionId, JSONObject body) throws Exception;
        JSONObject send(String sessionId, JSONObject frame) throws Exception;
        JSONObject poll(String sessionId, long after) throws Exception;
        void ack(String sessionId, long seq) throws Exception;
        void close(String sessionId) throws Exception;
        String centerId();
    }

    public static PeerCenterConnector active(String id) {
        return ACTIVE.get(id);
    }

    /** True only for a real, authenticated data channel; a signaling session is not enough. */
    public static boolean dataChannelOnline(String id) {
        PeerCenterConnector connector = ACTIVE.get(id);
        PeerNativeRtcTransport transport = connector == null ? null : connector.rtc;
        return transport != null && transport.online();
    }

    public static synchronized void ensure(Context context, String peerId) throws Exception {
        if (ACTIVE.containsKey(peerId)) return;
        PeerCenterConnector connector = new PeerCenterConnector(context, peerId);
        ACTIVE.put(peerId, connector);
        connector.start();
    }

    public static void closeAll() {
        for (PeerCenterConnector connector : new ArrayList<>(ACTIVE.values())) connector.close();
    }

    public static void closePeer(String id) {
        PeerCenterConnector connector = ACTIVE.get(id);
        if (connector != null) connector.close();
    }

    public static void networkChangedAll() {
        for (PeerCenterConnector connector : ACTIVE.values()) connector.networkChanged();
    }

    public static void reconnect(String id) {
        PeerCenterConnector connector = ACTIVE.get(id);
        if (connector != null) connector.networkChanged();
    }

    /** One queued frame. The sealed bytes are kept so a retransmission keeps its id and seq. */
    private static final class Pending {
        final JSONObject payload;
        JSONObject frame;
        long seq;

        Pending(JSONObject payload) {
            this.payload = payload;
        }
    }

    private final PeerStore store;
    private final Context context;
    private final String peerId;
    private final String role;
    private final Carrier carrier;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Deque<Pending> outgoing = new ArrayDeque<>();
    private volatile PeerNativeRtcTransport rtc;
    private volatile boolean closed;
    private volatile String sessionId = "";
    private volatile String centerId = "";
    private volatile String bindingId = "";
    private volatile int revision;
    private volatile long outgoingSeq;
    private volatile long incomingSeq;
    private volatile long lastAttempt;
    private volatile long lastOffer;
    private volatile int failures;

    private PeerCenterConnector(Context context, String peerId) throws Exception {
        this.context = context.getApplicationContext();
        this.store = PeerStore.get(context);
        this.peerId = peerId;
        JSONObject profile = store.peer(peerId);
        if ("REVOKED".equals(profile.optString("pairState")))
            throw new SecurityException("peer_revoked");
        this.role = "NODE".equals(profile.optString("localRole")) ? NODE : CONTROLLER;
        JSONObject signaling = store.signaling(peerId);
        this.bindingId = signaling.optString("bindingId", "");
        this.revision = signaling.optInt("revision", 0);
        // The auxiliary path requires a trusted description for this role's exact
        // origin. A pairing hint or an enrolled address is not trust.
        JSONObject hint = profile.optJSONObject("relayHint");
        String centerOrigin = NODE.equals(role)
                ? new com.blackkcold.simhub.AgentConfig(context).server()
                : (hint == null ? "" : hint.optString("origin", ""));
        CenterTrust trust = CenterTrustStore.get(context).effective();
        String trustRole = NODE.equals(role) ? CenterTrust.ROLE_NODE : CenterTrust.ROLE_CONTROLLER;
        if (trust == null || !trust.covers(trustRole, centerOrigin))
            throw new SecurityException("CENTER_SETUP_REQUIRED");
        this.carrier = NODE.equals(role)
                ? new NodeCarrier(new ApiClient(context))
                : new ControllerCarrier(new ControllerSecureClient(context, centerOrigin));
    }

    private void start() {
        worker.scheduleWithFixedDelay(this::tick, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public String route() {
        return "DIRECT";
    }

    public JSONObject request(JSONObject peer, String id, JSONObject request) throws Exception {
        PeerNativeRtcTransport current = rtc;
        if (closed || current == null) throw new IOException("center_peer_unavailable");
        return current.request(peer, id, request);
    }

    // -- state machine ------------------------------------------------------

    private void tick() {
        if (closed) return;
        try {
            if (!authorized()) {
                status("STOPPED", "peer_revoked");
                close();
                return;
            }
            JSONObject signaling = store.signaling(peerId);
            if (!signaling.optBoolean("consent")) {
                status("STOPPED", "signaling_consent_required");
                return;
            }
            if (System.currentTimeMillis() < lastAttempt) return;
            if (bindingId.isEmpty()) {
                if (!NODE.equals(role)) {
                    status("SETUP_REQUIRED", "CENTER_SETUP_REQUIRED");
                    lastAttempt = System.currentTimeMillis() + OFFER_INTERVAL_MS;
                    return;
                }
                registerBinding();
            }
            if (sessionId.isEmpty() && !openSession()) return;
            if (rtc == null) createTransport();
            offerIfStalled();
            flushOutgoing();
            pollFrames();
        } catch (Exception error) {
            // Pin mismatch, untrusted origin, revoked authorization or a transient
            // failure all fail closed with a fixed status; no raw message is stored.
            status("UNAVAILABLE", fixedCode(error));
            backoff();
        }
    }

    private void backoff() {
        failures = Math.min(failures + 1, 8);
        lastAttempt = System.currentTimeMillis() + Math.min(60000, 2000L * (1L << Math.min(5, failures)));
        sessionId = "";
        centerId = "";
    }

    private boolean authorized() throws Exception {
        return !"REVOKED".equals(store.peer(peerId).optString("pairState"));
    }

    private void offerIfStalled() {
        if (!CONTROLLER.equals(role)) return;
        PeerNativeRtcTransport current = rtc;
        if (current == null || current.online()) return;
        long now = System.currentTimeMillis();
        if (now - lastOffer < OFFER_INTERVAL_MS) return;
        lastOffer = now;
        try {
            current.restart();
        } catch (Exception ignored) {
            // A restart failure surfaces through the transport state.
        }
    }

    private void registerBinding() throws Exception {
        JSONObject peer = store.peer(peerId);
        JSONObject challenge = carrier.challenge("");
        long expires = System.currentTimeMillis() / 1000 + BINDING_TTL;
        int next = store.signaling(peerId).optInt("revision", revision) + 1;
        String nodeId = store.myId();
        String nodePublicKey = store.publicKey();
        String controllerPeerId = peer.getString("id");
        String controllerPublicKey = peer.getString("publicKey");
        String challengeId = challenge.getString("challengeId");
        String transcript = PeerSignalingProtocol.bindingTranscript(MODE, "", nodeId, nodePublicKey,
                controllerPeerId, controllerPublicKey, next, true, challengeId, expires);
        JSONObject body = new JSONObject().put("protocolVersion", MODE).put("bindingId", "")
                .put("nodeId", nodeId).put("nodePublicKey", nodePublicKey)
                .put("allowedControllerPeerId", controllerPeerId)
                .put("allowedControllerPublicKey", controllerPublicKey)
                .put("consentRevision", next).put("allowed", true)
                .put("challengeId", challengeId).put("expires", expires)
                .put("signature", PeerSignalingProtocol.signTranscript(store.identity().getPrivate(), transcript));
        JSONObject result = carrier.bind(body);
        bindingId = result.getString("bindingId");
        revision = result.optInt("acceptedRevision", next);
        // The node persists the binding so both sides agree on the same revision.
        store.setSignaling(peerId, true, bindingId, revision);
        status("WAITING", "");
    }

    private boolean openSession() throws Exception {
        return NODE.equals(role) ? joinNodeSession() : createControllerSession();
    }

    private boolean joinNodeSession() throws Exception {
        JSONArray sessions = carrier.listSessions().optJSONArray("sessions");
        if (sessions == null) {
            status("WAITING", "center_no_session");
            return false;
        }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject candidate = sessions.getJSONObject(i);
            if (!bindingId.equals(candidate.optString("bindingId"))) continue;
            if (!peerId.equals(candidate.optString("controllerPeerId"))) continue;
            String id = candidate.getString("sessionId");
            if ("awaiting_node".equals(candidate.optString("state"))) {
                JSONObject challenge = carrier.challenge("");
                String challengeId = challenge.getString("challengeId");
                long expires = candidate.getLong("expires");
                String center = candidate.optString("centerId");
                JSONObject peer = store.peer(peerId);
                String transcript = PeerSignalingProtocol.sessionTranscript(MODE, NODE, center, id, bindingId,
                        store.myId(), store.publicKey(), peerId, peer.getString("publicKey"),
                        candidate.optInt("revision"), challengeId, expires);
                JSONObject body = new JSONObject().put("protocolVersion", MODE).put("sessionId", id)
                        .put("bindingId", bindingId).put("centerId", center)
                        .put("revision", candidate.optInt("revision")).put("challengeId", challengeId)
                        .put("expires", expires)
                        .put("signature", PeerSignalingProtocol.signTranscript(store.identity().getPrivate(), transcript));
                carrier.join(id, body);
            }
            sessionId = id;
            centerId = candidate.optString("centerId");
            revision = candidate.optInt("revision", revision);
            incomingSeq = 0;
            outgoingSeq = 0;
            failures = 0;
            synchronized (outgoing) {
                outgoing.clear();
            }
            status("READY", "");
            return true;
        }
        status("WAITING", "center_no_session");
        return false;
    }

    private boolean createControllerSession() throws Exception {
        JSONObject challenge = carrier.challenge(bindingId);
        JSONObject peer = store.peer(peerId);
        String id = PeerCrypto.b64(PeerCrypto.random(32));
        String center = carrier.centerId();
        long expires = System.currentTimeMillis() / 1000 + SESSION_MAX;
        String controllerPeerId = store.myId();
        String controllerPublicKey = store.publicKey();
        String nodeId = challenge.getString("nodeId");
        String nodePublicKey = challenge.getString("nodePublicKey");
        int sessionRevision = challenge.getInt("revision");
        String challengeId = challenge.getString("challengeId");
        String transcript = PeerSignalingProtocol.sessionTranscript(MODE, CONTROLLER, center, id, bindingId,
                nodeId, nodePublicKey, controllerPeerId, controllerPublicKey, sessionRevision, challengeId, expires);
        JSONObject body = new JSONObject().put("protocolVersion", MODE).put("sessionId", id)
                .put("bindingId", bindingId).put("nodeId", nodeId).put("nodePublicKey", nodePublicKey)
                .put("controllerPeerId", controllerPeerId).put("controllerPublicKey", controllerPublicKey)
                .put("centerId", center).put("revision", sessionRevision).put("challengeId", challengeId)
                .put("expires", expires)
                .put("signature", PeerSignalingProtocol.signTranscript(store.identity().getPrivate(), transcript));
        JSONObject result = carrier.createSession(body);
        sessionId = result.getString("sessionId");
        centerId = center;
        revision = sessionRevision;
        ((ControllerCarrier) carrier).sessionToken = result.optString("sessionToken");
        incomingSeq = 0;
        outgoingSeq = 0;
        failures = 0;
        synchronized (outgoing) {
            outgoing.clear();
        }
        status("READY", "");
        return true;
    }

    private void createTransport() throws Exception {
        final boolean initiator = CONTROLLER.equals(role);
        rtc = new PeerNativeRtcTransport(context, store.peer(peerId), false,
                signal -> submit(() -> queueSignal(signal)));
        lastOffer = System.currentTimeMillis();
        submit(() -> {
            try {
                PeerNativeRtcTransport current = rtc;
                if (current != null) current.init(PeerCenterProtocol.iceServers(context, peerId), initiator);
            } catch (Exception error) {
                status("UNAVAILABLE", "peer_native_init_failed");
            }
        });
    }

    private void queueSignal(JSONObject signal) {
        JSONObject payload = payloadFor(signal);
        if (payload == null) return;
        synchronized (outgoing) {
            if (outgoing.size() >= MAX_OUTGOING) outgoing.pollFirst();
            outgoing.addLast(new Pending(payload));
        }
        flushOutgoing();
    }

    /** Sends only the head, sealing it once so a retransmission keeps id, seq and nonce. */
    private void flushOutgoing() {
        String session = sessionId;
        if (session.isEmpty()) return;
        Pending head;
        synchronized (outgoing) {
            head = outgoing.peekFirst();
        }
        if (head == null) return;
        try {
            if (head.frame == null) {
                byte[] key = store.key(peerId);
                try {
                    synchronized (outgoing) {
                        head.seq = outgoingSeq + 1;
                        head.frame = PeerSignalingProtocol.seal(PeerSignalingProtocol.deriveKey(key), session,
                                PeerCrypto.b64(PeerCrypto.random(18)), role, head.seq,
                                System.currentTimeMillis() / 1000 + PeerSignalingProtocol.MAX_LIFETIME,
                                revision, head.payload);
                    }
                } finally {
                    Arrays.fill(key, (byte) 0);
                }
            }
            carrier.send(session, head.frame);
            synchronized (outgoing) {
                outgoingSeq = head.seq;
                outgoing.pollFirst();
            }
        } catch (Exception error) {
            if (retryable(error)) return;
            synchronized (outgoing) {
                outgoing.pollFirst();
            }
        }
    }

    private void pollFrames() throws Exception {
        JSONObject result = carrier.poll(sessionId, incomingSeq);
        JSONArray messages = result.optJSONArray("messages");
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject entry = messages.getJSONObject(i);
            long seq = entry.getLong("seq");
            if (seq <= incomingSeq) continue;
            byte[] key = store.key(peerId);
            JSONObject payload;
            try {
                payload = PeerSignalingProtocol.open(PeerSignalingProtocol.deriveKey(key), sessionId,
                        CONTROLLER.equals(role) ? NODE : CONTROLLER, entry.getJSONObject("frame"),
                        System.currentTimeMillis() / 1000);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            incomingSeq = seq;
            carrier.ack(sessionId, seq);
            deliver(payload);
        }
    }

    private void deliver(JSONObject payload) {
        JSONObject signal = PeerSignalingProtocol.transportSignal(payload);
        if (signal == null) return;
        PeerNativeRtcTransport current = rtc;
        if (current == null) return;
        try {
            current.signal(signal);
        } catch (Exception ignored) {
            // The transport owns its own failure reporting.
        }
    }

    private static JSONObject payloadFor(JSONObject signal) {
        try {
            JSONObject description = signal.optJSONObject("description");
            if (description != null) {
                String type = description.optString("type", "");
                if (!"offer".equals(type) && !"answer".equals(type)) return null;
                String sdp = description.optString("sdp", "");
                if (sdp.isEmpty() || sdp.length() > 120000) return null;
                return new JSONObject().put("kind", "offer".equals(type) ? "OFFER" : "ANSWER")
                        .put("description", new JSONObject().put("type", type).put("sdp", sdp));
            }
            JSONObject candidate = signal.optJSONObject("candidate");
            if (candidate != null) {
                String value = candidate.optString("candidate", "");
                if (value.isEmpty() || value.length() > 512) return null;
                return new JSONObject().put("kind", "ICE").put("candidate", new JSONObject()
                        .put("candidate", value)
                        .put("sdpMid", candidate.optString("sdpMid", ""))
                        .put("sdpMLineIndex", candidate.optInt("sdpMLineIndex", 0)));
            }
            return null;
        } catch (Exception error) {
            // An unencodable signal is dropped rather than sent malformed.
            return null;
        }
    }

    public void networkChanged() {
        submit(() -> {
            PeerNativeRtcTransport current = rtc;
            rtc = null;
            if (current != null) current.close();
            synchronized (outgoing) {
                outgoing.clear();
            }
            sessionId = "";
            centerId = "";
            lastAttempt = 0;
            failures = 0;
            lastOffer = 0;
            tick();
        });
    }

    private void submit(Runnable task) {
        if (closed) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Late callback after close.
        }
    }

    private static boolean retryable(Exception error) {
        int status = status(error);
        return status == 409 || status == 429 || status >= 500 || status == 0;
    }

    private void status(String state, String error) {
        try {
            synchronized (store) {
                JSONObject profile = store.peer(peerId);
                profile.put("discoveryState", state).put("connectionError", error);
                store.update(profile);
            }
        } catch (Exception ignored) {
            // A status write never changes authorization.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ACTIVE.remove(peerId, this);
        PeerNativeRtcTransport current = rtc;
        rtc = null;
        if (current != null) current.close();
        final String id = sessionId;
        sessionId = "";
        worker.shutdownNow();
        status("STOPPED", "");
        if (!id.isEmpty()) try {
            CLOSER.execute(() -> {
                try {
                    carrier.close(id);
                } catch (Exception ignored) {
                    // The server expires the session regardless.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown path.
        }
    }

    /** Fixed status codes only: raw messages, hosts and pins never reach diagnostics. */
    static String fixedCode(Exception error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) return "center_signaling_unavailable";
        if (error instanceof SecurityException && message.matches("[A-Za-z0-9_]{1,64}")) return message;
        if (status(error) >= 400 && message.matches("[A-Za-z0-9_]{1,64}")) return message;
        return "center_signaling_unavailable";
    }

    static int status(Exception error) {
        if (error instanceof ApiClient.ApiFailure failure) return failure.status;
        if (error instanceof ControllerSecureClient.ApiError failure) return failure.getStatus();
        return 0;
    }

    // -- carriers -----------------------------------------------------------

    private static final class NodeCarrier implements Carrier {
        private final ApiClient api;

        NodeCarrier(ApiClient api) {
            this.api = api;
        }

        public JSONObject challenge(String bindingId) throws Exception {
            return api.signalingChallenge();
        }

        public JSONObject bind(JSONObject body) throws Exception {
            return api.signalingBinding(body);
        }

        public JSONObject createSession(JSONObject body) throws Exception {
            throw new SecurityException("node_creates_no_session");
        }

        public JSONObject listSessions() throws Exception {
            return api.signalingSessions();
        }

        public JSONObject join(String sessionId, JSONObject body) throws Exception {
            return api.signalingJoin(sessionId, body);
        }

        public JSONObject send(String sessionId, JSONObject frame) throws Exception {
            return api.signalingSend(sessionId, frame);
        }

        public JSONObject poll(String sessionId, long after) throws Exception {
            return api.signalingPoll(sessionId, after);
        }

        public void ack(String sessionId, long seq) throws Exception {
            api.signalingAck(sessionId, seq);
        }

        public void close(String sessionId) throws Exception {
            api.signalingClose(sessionId);
        }

        public String centerId() {
            return "";
        }
    }

    private static final class ControllerCarrier implements Carrier {
        private final ControllerSecureClient client;
        volatile String sessionToken = "";

        ControllerCarrier(ControllerSecureClient client) {
            this.client = client;
        }

        public JSONObject challenge(String bindingId) throws Exception {
            return client.api("/api/v1/controllers/peer-signaling/challenge", "POST",
                    new JSONObject().put("bindingId", bindingId));
        }

        public JSONObject bind(JSONObject body) throws Exception {
            throw new SecurityException("controller_registers_no_binding");
        }

        public JSONObject createSession(JSONObject body) throws Exception {
            return client.api("/api/v1/controllers/peer-signaling/sessions", "POST", body);
        }

        public JSONObject listSessions() throws Exception {
            return new JSONObject().put("sessions", new JSONArray());
        }

        public JSONObject join(String sessionId, JSONObject body) throws Exception {
            throw new SecurityException("controller_joins_its_own_session");
        }

        public JSONObject send(String sessionId, JSONObject frame) throws Exception {
            return token("/api/v1/controllers/peer-signaling/sessions/" + sessionId + "/signal",
                    new JSONObject().put("frame", frame));
        }

        public JSONObject poll(String sessionId, long after) throws Exception {
            return client.signaling("/api/v1/controllers/peer-signaling/sessions/" + sessionId
                    + "/poll?after=" + Math.max(0, after), null, sessionToken);
        }

        public void ack(String sessionId, long seq) throws Exception {
            token("/api/v1/controllers/peer-signaling/sessions/" + sessionId + "/ack",
                    new JSONObject().put("seq", seq));
        }

        public void close(String sessionId) throws Exception {
            token("/api/v1/controllers/peer-signaling/sessions/" + sessionId + "/close", new JSONObject());
        }

        public String centerId() {
            return client.controllerId();
        }

        /**
         * The one-time session token scopes frame operations to the session this
         * controller created; the admin cookie alone is not the signaling grant.
         */
        private JSONObject token(String path, JSONObject body) throws Exception {
            String value = sessionToken;
            if (value.isEmpty()) throw new SecurityException("CENTER_AUTH_REQUIRED");
            return client.signaling(path, body, value);
        }
    }
}
