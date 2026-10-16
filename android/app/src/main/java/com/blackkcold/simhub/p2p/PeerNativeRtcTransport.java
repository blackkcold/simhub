package com.blackkcold.simhub.p2p;

import android.content.Context;
import org.json.*;
import org.webrtc.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Data-only native WebRTC transport. Replaces the retired script-driven engine:
 * no JavaScript, no embedded browser, no remote code, no audio/video tracks or capture.
 *
 * All state changes live on one serial executor; native callbacks are posted
 * onto it and validated against a session epoch. Statistics from the real
 * selected candidate pair decide LAN/DIRECT/TURN, and an unknown route never
 * counts as DIRECT. TURN is configured solely when the pair carries explicit
 * relay consent; without it a relayed path is torn down instead of carrying
 * business frames.
 */
public final class PeerNativeRtcTransport implements HubTransport, AutoCloseable {
    public interface Signals { void send(JSONObject payload); }
    private static final String LABEL = "simhub-peer-v1";
    private static final int MAX_FRAME = 600000, CHUNK = 16000, MAX_ASSEMBLIES = 8;
    private static final long MAX_BUFFERED = 2_000_000;
    private static final int ROUTE_ATTEMPTS = 20;
    private static final Object FACTORY_LOCK = new Object();
    private static Context appContext;
    private static PeerConnectionFactory factory;
    private static boolean initialized;

    private final PeerStore store;
    private final JSONObject profile;
    private final boolean consent;
    private final Signals signals;
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService schedule = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, Assembly> assembling = new HashMap<>();
    private final List<IceCandidate> candidateQueue = new ArrayList<>();
    private volatile CompletableFuture<Void> connected = new CompletableFuture<>();
    private volatile PeerConnection pc;
    private volatile DataChannel channel;
    private volatile JSONArray servers = new JSONArray();
    private volatile boolean closed, ready, online, initiator;
    private volatile String route = "DIRECT";
    private volatile int epoch;

    public PeerNativeRtcTransport(Context context, JSONObject profile, boolean consent, Signals signals) {
        synchronized (FACTORY_LOCK) {
            if (appContext == null) appContext = context.getApplicationContext();
        }
        this.store = PeerStore.get(context);
        this.profile = profile;
        this.consent = consent;
        this.signals = signals;
    }

    /** True once the native engine is initialized; there is no script or remote code to load. */
    public boolean engineReady() {
        synchronized (FACTORY_LOCK) {
            // A global factory alone does not prove this instance owns a live RTC connection.
            return initialized && factory != null && pc != null && !closed;
        }
    }

    public void init(JSONArray servers, boolean initiator) throws Exception {
        if (closed) throw new IOException("peer_transport_closed");
        final JSONArray endpoints = servers == null ? new JSONArray() : servers;
        final boolean originator = initiator;
        CompletableFuture<Void> prepared = new CompletableFuture<>();
        dispatch.execute(() -> {
            try {
                reset(endpoints, originator);
                prepared.complete(null);
            } catch (Throwable error) {
                prepared.completeExceptionally(new IOException("peer_native_init_failed", error));
            }
        });
        prepared.get(10, TimeUnit.SECONDS);
    }

    public void signal(JSONObject payload) throws Exception {
        if (closed || payload == null) return;
        final JSONObject value = payload;
        dispatch.execute(() -> {
            try {
                receiveSignal(value);
            } catch (Exception ignored) {
                // Untrusted signaling never grants access and is never logged.
            }
        });
    }

    public void restart() throws Exception {
        if (closed) return;
        dispatch.execute(() -> {
            try {
                if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED || channelClosed()) {
                    reset(servers, initiator);
                    return;
                }
                ready = false;
                final int own = epoch;
                pc.restartIce();
                pc.createOffer(new SafeSdp() {
                    protected void onOffer(SessionDescription sdp) { setLocal(sdp); }
                    protected void onFailure() { onTransportLostIf(own); }
                }, new MediaConstraints());
            } catch (Exception ignored) {
                // Restart failures surface through the transport state, never as logs.
            }
        });
    }

    public String route() { return route; }

    public boolean online() { return online; }

    public JSONObject request(JSONObject peer, String id, JSONObject req) throws Exception {
        try {
            connected.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            throw new IOException("peer_ice_not_connected", error);
        }
        if (closed || !online) throw new IOException("peer_ice_not_connected");
        JSONObject current = store.peer(peer.getString("id"));
        if (!ConnectionPolicy.valueOf(current.getString("policy")).permits(route, current.optBoolean("fallbackConsent"),
                "ACTIVE".equals(current.optString("relayBindingState"))))
            throw new SecurityException("peer_route_not_authorized");
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        if (pending.putIfAbsent(id, future) != null) throw new IllegalStateException("request_already_pending");
        byte[] key = store.key(peer.getString("id"));
        try {
            JSONObject frame = PeerProtocol.seal(key, store.myId(), peer.getString("id"), id,
                    System.currentTimeMillis() / 1000 + 120, req);
            final String encoded = frame.toString();
            dispatch.execute(() -> {
                try {
                    sendFrame(encoded);
                } catch (Exception error) {
                    if (pending.remove(id, future)) future.completeExceptionally(error);
                }
            });
            return future.get(20, TimeUnit.SECONDS);
        } finally {
            pending.remove(id);
            Arrays.fill(key, (byte) 0);
        }
    }

    public void close() {
        closed = true;
        online = false;
        ready = false;
        connected.completeExceptionally(new IOException("peer_transport_closed"));
        for (CompletableFuture<JSONObject> future : pending.values())
            future.completeExceptionally(new IOException("peer_transport_closed"));
        pending.clear();
        try {
            dispatch.execute(this::disposePeerConnection);
            dispatch.execute(() -> {
                assembling.clear();
                candidateQueue.clear();
                dispatch.shutdown();
                schedule.shutdownNow();
            });
        } catch (RejectedExecutionException ignored) {
            // Already closed.
        }
    }

    // -- serial domain ------------------------------------------------------

    private void post(Runnable task) {
        try {
            dispatch.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Late native callback after close.
        }
    }

    private void postGuarded(Runnable task) {
        post(() -> {
            try {
                task.run();
            } catch (Exception ignored) {
                // Callback bodies never throw into webrtc threads or the serial domain.
            }
        });
    }

    private boolean valid(int own) { return own == epoch && !closed; }

    private boolean channelClosed() {
        DataChannel current = channel;
        return current == null || current.state() == DataChannel.State.CLOSED || current.state() == DataChannel.State.CLOSING;
    }

    // -- engine lifecycle ---------------------------------------------------

    private static PeerConnectionFactory factory() {
        synchronized (FACTORY_LOCK) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                        .builder(appContext).createInitializationOptions());
                initialized = true;
            }
            if (factory == null) factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            return factory;
        }
    }

    private void reset(JSONArray endpoints, boolean originator) throws Exception {
        epoch++;
        servers = endpoints;
        initiator = originator;
        disposePeerConnection();
        assembling.clear();
        candidateQueue.clear();
        ready = false;
        online = false;
        if (connected.isDone()) connected = new CompletableFuture<>();
        final int own = epoch;
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        for (int index = 0; index < endpoints.length(); index++) {
            JSONObject item = endpoints.getJSONObject(index);
            JSONArray urls = item.optJSONArray("urls");
            if (urls == null) continue;
            List<String> allowed = new ArrayList<>();
            for (int url = 0; url < urls.length(); url++) {
                String value = urls.getString(url);
                if (value.startsWith("stun:") || value.startsWith("stuns:")) allowed.add(value);
                else if (consent && (value.startsWith("turn:") || value.startsWith("turns:"))) allowed.add(value);
            }
            if (allowed.isEmpty()) continue;
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(allowed);
            if (item.has("username")) builder.setUsername(item.getString("username"));
            if (item.has("credential")) builder.setPassword(item.getString("credential"));
            ice.add(builder.createIceServer());
        }
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        PeerConnection connection = factory().createPeerConnection(config, observer(own));
        if (connection == null) throw new IOException("peer_native_engine_unavailable");
        pc = connection;
        if (originator) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            DataChannel outgoing = connection.createDataChannel(LABEL, init);
            if (outgoing == null) throw new IOException("peer_native_channel_unavailable");
            attach(outgoing, own);
            connection.createOffer(new SafeSdp() {
                protected void onOffer(SessionDescription sdp) { setLocal(sdp); }
                protected void onFailure() { onTransportLostIf(own); }
            }, new MediaConstraints());
        }
    }

    private PeerConnection.Observer observer(final int own) {
        return new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState state) {}
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
            public void onIceConnectionReceivingChange(boolean receiving) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            public void onIceCandidate(IceCandidate candidate) {
                if (candidate == null || candidate.sdp == null) return;
                post(() -> {
                    if (valid(own)) sendCandidate(candidate);
                });
            }
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            public void onAddStream(MediaStream stream) {}
            public void onRemoveStream(MediaStream stream) {}
            public void onDataChannel(DataChannel incoming) {
                post(() -> {
                    if (valid(own)) attach(incoming, own);
                });
            }
            public void onRenegotiationNeeded() {}
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                post(() -> {
                    if (!valid(own)) return;
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        classifyRoute(own, 0);
                    } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED
                            || state == PeerConnection.PeerConnectionState.FAILED
                            || state == PeerConnection.PeerConnectionState.CLOSED) {
                        onTransportLost();
                    }
                });
            }
        };
    }

    private void attach(DataChannel incoming, final int own) {
        if (incoming == null) return;
        channel = incoming;
        incoming.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long amount) {}
            public void onStateChange() {
                DataChannel current = channel;
                if (current == null) return;
                final DataChannel.State state = current.state();
                post(() -> {
                    if (!valid(own)) return;
                    if (state == DataChannel.State.OPEN) classifyRoute(own, 0);
                    else if (state == DataChannel.State.CLOSED) onTransportLost();
                });
            }
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer == null || buffer.binary || buffer.data == null) return;
                String text;
                try {
                    byte[] raw = new byte[buffer.data.remaining()];
                    buffer.data.get(raw);
                    text = new String(raw, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return;
                }
                final String value = text;
                post(() -> onChunk(own, value));
            }
        });
        if (incoming.state() == DataChannel.State.OPEN) classifyRoute(own, 0);
    }

    private void disposePeerConnection() {
        DataChannel current = channel;
        channel = null;
        if (current != null) {
            try { current.unregisterObserver(); } catch (Exception ignored) { }
            try { current.close(); } catch (Exception ignored) { }
            try { current.dispose(); } catch (Exception ignored) { }
        }
        PeerConnection connection = pc;
        pc = null;
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) { }
            try { connection.dispose(); } catch (Exception ignored) { }
        }
    }

    private void onTransportLostIf(int own) {
        if (valid(own)) onTransportLost();
    }

    /** Runs on the serial domain only. Clears transport state without releasing the engine. */
    private void onTransportLost() {
        ready = false;
        online = false;
        if (connected.isDone()) connected = new CompletableFuture<>();
        for (CompletableFuture<JSONObject> future : pending.values())
            future.completeExceptionally(new IOException("peer_channel_closed"));
        pending.clear();
        assembling.clear();
        candidateQueue.clear();
    }

    // -- signaling ----------------------------------------------------------

    private void sendSignal(JSONObject payload) {
        if (closed) return;
        try {
            signals.send(payload);
        } catch (Exception ignored) {
            // The signaling carrier owns its own failure reporting.
        }
    }

    private void setLocal(final SessionDescription sdp) {
        final PeerConnection connection = pc;
        if (connection == null || closed) return;
        final int own = epoch;
        connection.setLocalDescription(new SafeSdp() {
            protected void onSet() {
                if (!valid(own)) return;
                try {
                    sendSignal(new JSONObject().put("description", describe(sdp)));
                } catch (Exception ignored) {
                    // Encoding failures never take the session down.
                }
            }
            protected void onFailure() { onTransportLostIf(own); }
        }, sdp);
    }

    private void receiveSignal(JSONObject payload) throws Exception {
        JSONObject description = payload.optJSONObject("description");
        JSONObject candidate = payload.optJSONObject("candidate");
        // The answering peer has no DataChannel until the remote offer is applied.
        // Resetting on a missing channel discards previously queued ICE candidates.
        if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
            reset(servers, false);
        }
        final int own = epoch;
        if (candidate != null && !consent && isRelay(candidate.optString("candidate"))) return;
        if (description != null) {
            String type = description.optString("type", "");
            if (!"offer".equals(type) && !"answer".equals(type)) return;
            String sdpText = description.optString("sdp", "");
            if (sdpText.isEmpty() || sdpText.length() > 120000) return;
            if (!consent) sdpText = stripRelayLines(sdpText);
            final SessionDescription remote = new SessionDescription(
                    "offer".equals(type) ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER, sdpText);
            final PeerConnection connection = pc;
            if (connection == null) return;
            connection.setRemoteDescription(new SafeSdp() {
                protected void onSet() {
                    if (!valid(own)) return;
                    try {
                        for (IceCandidate queued : candidateQueue) connection.addIceCandidate(queued);
                        candidateQueue.clear();
                        if (remote.type == SessionDescription.Type.OFFER) {
                            connection.createAnswer(new SafeSdp() {
                                protected void onOffer(SessionDescription answer) { setLocal(answer); }
                                protected void onFailure() { onTransportLostIf(own); }
                            }, new MediaConstraints());
                        }
                    } catch (Exception ignored) {
                        // Candidate or answer failures surface through transport state.
                    }
                }
                protected void onFailure() { onTransportLostIf(own); }
            }, remote);
        }
        if (candidate != null) {
            String sdpText = candidate.optString("candidate", "");
            if (sdpText.isEmpty() || sdpText.length() > 512) return;
            IceCandidate ice = new IceCandidate(candidate.optString("sdpMid", "0"),
                    candidate.optInt("sdpMLineIndex", 0), sdpText);
            PeerConnection connection = pc;
            if (connection == null) return;
            if (connection.getRemoteDescription() != null) connection.addIceCandidate(ice);
            else if (candidateQueue.size() < 128) candidateQueue.add(ice);
        }
    }

    private void sendCandidate(IceCandidate candidate) {
        try {
            JSONObject value = new JSONObject().put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex);
            sendSignal(new JSONObject().put("candidate", value));
        } catch (Exception ignored) {
            // Encoding failures never take the session down.
        }
    }

    private static JSONObject describe(SessionDescription sdp) {
        JSONObject value = new JSONObject();
        try {
            value.put("type", sdp.type == SessionDescription.Type.OFFER ? "offer" : "answer")
                    .put("sdp", sdp.description);
        } catch (Exception ignored) {
            // Never fails for plain strings.
        }
        return value;
    }

    private static boolean isRelay(String sdp) {
        return sdp != null && sdp.matches(".*\\btyp relay\\b.*");
    }

    private static String stripRelayLines(String sdp) {
        String[] lines = sdp.split("\r\n", -1);
        StringBuilder kept = new StringBuilder(sdp.length());
        for (String line : lines) {
            if (line.startsWith("a=candidate:") && isRelay(line)) continue;
            kept.append(line).append("\r\n");
        }
        return kept.toString();
    }

    // -- route classification ------------------------------------------------

    private void classifyRoute(final int own, final int attempt) {
        final PeerConnection connection = pc;
        if (!valid(own) || connection == null) return;
        if (connection.connectionState() != PeerConnection.PeerConnectionState.CONNECTED
                || channelClosed()) {
            retryRoute(own, attempt, connection);
            return;
        }
        connection.getStats(report -> post(() -> {
            if (!valid(own) || pc != connection) return;
            Map<String, RTCStats> stats = report.getStatsMap();
            String selectedId = null;
            for (RTCStats row : stats.values()) {
                if ("transport".equals(row.getType())) {
                    Object value = row.getMembers().get("selectedCandidatePairId");
                    if (value instanceof String text && !text.isEmpty()) selectedId = text;
                }
            }
            RTCStats pair = selectedId == null ? null : stats.get(selectedId);
            if (pair == null) {
                for (RTCStats row : stats.values()) {
                    if (!"candidate-pair".equals(row.getType())) continue;
                    if ("succeeded".equals(String.valueOf(row.getMembers().get("state")))
                            && Boolean.TRUE.equals(row.getMembers().get("nominated"))) {
                        pair = row;
                        break;
                    }
                }
            }
            RTCStats local = pair == null ? null : stats.get(String.valueOf(pair.getMembers().get("localCandidateId")));
            RTCStats remote = pair == null ? null : stats.get(String.valueOf(pair.getMembers().get("remoteCandidateId")));
            if (pair == null || local == null || remote == null) {
                retryRoute(own, attempt, connection);
                return;
            }
            String localType = String.valueOf(local.getMembers().get("candidateType"));
            String remoteType = String.valueOf(remote.getMembers().get("candidateType"));
            String selected;
            if ("relay".equals(localType) || "relay".equals(remoteType)) selected = "TURN";
            else if ("host".equals(localType) && "host".equals(remoteType)
                    && privateAddress(candidateAddress(local)) && privateAddress(candidateAddress(remote))) selected = "LAN";
            else selected = "DIRECT";
            if ("TURN".equals(selected) && !consent) {
                // Without explicit relay consent a relayed path must never carry business frames.
                route = selected;
                onTransportLost();
                disposePeerConnection();
                return;
            }
            route = selected;
            ready = true;
            online = true;
            connected.complete(null);
        }));
    }

    private void retryRoute(final int own, final int attempt, final PeerConnection connection) {
        if (attempt >= ROUTE_ATTEMPTS) {
            onTransportLost();
            disposePeerConnection();
            return;
        }
        try {
            schedule.schedule(() -> post(() -> {
                if (valid(own) && pc == connection) classifyRoute(own, attempt + 1);
            }), 100, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    private static String candidateAddress(RTCStats candidate) {
        Object address = candidate.getMembers().get("address");
        if (address == null) address = candidate.getMembers().get("ip");
        return address == null ? "" : String.valueOf(address);
    }

    private static boolean privateAddress(String address) {
        if (address == null || address.isEmpty()) return false;
        String value = address.toLowerCase(Locale.ROOT);
        String[] parts = value.split("\\.");
        if (parts.length == 4) {
            try {
                int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
                return first == 10 || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168) || (first == 169 && second == 254);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd");
    }

    // -- data channel ---------------------------------------------------------

    private static final class Assembly {
        final String[] parts;
        int filled;
        Assembly(int size) { parts = new String[size]; }
    }

    private void onChunk(int own, String raw) {
        if (!valid(own)) return;
        if (!ready) return;  // frames before a confirmed route are dropped, never dispatched
        boolean fatal = false;
        try {
            JSONObject part = new JSONObject(raw);
            String id = part.getString("id");
            int count = part.getInt("n"), index = part.getInt("i");
            String data = part.getString("d");
            if (id.length() > 80 || count < 1 || count > 40 || index < 0 || index >= count || data.length() > CHUNK)
                throw new SecurityException("peer_chunk_invalid");
            Assembly assembly = assembling.get(id);
            if (assembly == null) {
                if (assembling.size() >= MAX_ASSEMBLIES) throw new SecurityException("peer_assembly_capacity");
                assembly = new Assembly(count);
                assembling.put(id, assembly);
                final String key = id;
                try {
                    schedule.schedule(() -> post(() -> assembling.remove(key)), 15, TimeUnit.SECONDS);
                } catch (RejectedExecutionException ignored) {
                    // Closing.
                }
            }
            if (assembly.parts.length != count || assembly.parts[index] != null)
                throw new SecurityException("peer_chunk_conflict");
            assembly.parts[index] = data;
            assembly.filled++;
            if (assembly.filled == count) {
                assembling.remove(id);
                StringBuilder joined = new StringBuilder(count * CHUNK);
                for (String piece : assembly.parts) joined.append(piece);
                String frame = joined.toString();
                if (frame.length() > MAX_FRAME) throw new SecurityException("peer_frame_too_large");
                try {
                    incoming(frame);
                } catch (Exception ignored) {
                    // Content and crypto failures never grant access and are never logged.
                }
            }
        } catch (SecurityException error) {
            fatal = true;
        } catch (Exception ignored) {
            fatal = true;
        }
        if (fatal) onTransportLost();
    }

    private void sendFrame(String frame) {
        DataChannel current = channel;
        if (!ready || current == null || current.state() != DataChannel.State.OPEN)
            throw new IllegalStateException("peer_channel_not_ready");
        if (frame == null || frame.length() > MAX_FRAME) throw new IllegalStateException("peer_frame_too_large");
        if (current.bufferedAmount() > MAX_BUFFERED) throw new IllegalStateException("peer_channel_backpressure");
        String id;
        try {
            id = new JSONObject(frame).getString("id");
        } catch (Exception error) {
            throw new IllegalStateException("peer_frame_invalid", error);
        }
        int count = (frame.length() + CHUNK - 1) / CHUNK;
        for (int index = 0; index < count; index++) {
            String piece = frame.substring(index * CHUNK, Math.min(frame.length(), (index + 1) * CHUNK));
            String part;
            try {
                part = new JSONObject().put("id", id).put("i", index).put("n", count).put("d", piece).toString();
            } catch (Exception error) {
                throw new IllegalStateException("peer_frame_invalid", error);
            }
            if (!current.send(new DataChannel.Buffer(ByteBuffer.wrap(PeerCrypto.utf8(part)), false)))
                throw new IllegalStateException("peer_channel_send_failed");
        }
    }

    private void incoming(String raw) throws Exception {
        if (closed || !online) throw new SecurityException("peer_route_unconfirmed");
        JSONObject frame = new JSONObject(raw);
        String peerId = profile.getString("id");
        JSONObject current = store.peer(peerId);
        byte[] key = store.key(peerId);
        try {
            JSONObject plaintext = PeerProtocol.open(key, peerId, store.myId(), frame, System.currentTimeMillis() / 1000);
            String id = frame.getString("id");
            if ("CONTROLLER".equals(current.optString("localRole"))) {
                CompletableFuture<JSONObject> future = pending.get(id);
                if (future != null) future.complete(plaintext);
            } else {
                if ("TURN".equals(route) && (!current.optBoolean("fallbackConsent")
                        || !"P2P_RELAY_FALLBACK".equals(current.optString("policy"))))
                    throw new SecurityException("peer_fallback_not_allowed");
                JSONObject result = new PeerRpc(store).dispatch(peerId, id, plaintext);
                store.markTransport(peerId, route);
                JSONObject response = PeerProtocol.seal(key, store.myId(), peerId, id, frame.getLong("expires"), result);
                sendFrame(response.toString());
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** SDP observers arrive on webrtc threads: their outcomes are reposted onto the serial domain. */
    private abstract class SafeSdp implements SdpObserver {
        public final void onCreateSuccess(SessionDescription sdp) {
            postGuarded(() -> onOffer(sdp));
        }
        public final void onSetSuccess() {
            postGuarded(this::onSet);
        }
        public final void onCreateFailure(String error) {
            postGuarded(this::onFailure);
        }
        public final void onSetFailure(String error) {
            postGuarded(this::onFailure);
        }
        protected void onOffer(SessionDescription sdp) {}
        protected void onSet() {}
        protected void onFailure() {}
    }
}
