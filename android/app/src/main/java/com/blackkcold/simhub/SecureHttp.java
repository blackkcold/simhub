package com.blackkcold.simhub;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The single HTTP client used for every JSON request that carries a node token or a
 * controller session. Requests that target a center with a trusted description are
 * pinned; requests to an origin with no matching pin fail before any byte is written.
 *
 * <p>One shared base instance owns the connection pool, dispatcher and threads.
 * Pinned clients are derived from it, so pinning never creates a second, idle client
 * or a second connection pool.
 *
 * <p>OkHttp only enforces a pin when the pinner has an entry for the request host,
 * so the origin gate below is not optional decoration: it is what stops a request
 * from silently being sent unpinned to an unexpected host.
 */
public final class SecureHttp {
    /** The target origin is not the exact origin this role is pinned for. */
    public static final class OriginRejected extends SecurityException {
        public OriginRejected() {
            super("center_origin_not_trusted");
        }
    }

    /** No usable pin set exists for the requested role and origin. */
    public static final class PinMissing extends SecurityException {
        public PinMissing() {
            super("center_pin_missing");
        }
    }

    /** Fixed runtime codes; raw URLs and exception causes never leave this class. */
    public static final String TLS_PIN_MISMATCH = "CENTER_TLS_PIN_MISMATCH";
    public static final String AUTH_REQUIRED = "CENTER_AUTH_REQUIRED";

    private static final int MAX_BODY_BYTES = BoundedHttpReader.MAX_JSON_BYTES;
    private static final Map<String, OkHttpClient> PINNED = new ConcurrentHashMap<>();

    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private SecureHttp() {
    }

    /** Shared, unpinned client for callers with no trusted description. */
    public static OkHttpClient base() {
        return BASE;
    }

    /**
     * Strictly pinned client for one role and one exact origin. Throws when the
     * description does not cover the origin or carries no usable pin.
     */
    public static OkHttpClient pinned(CenterTrust trust, String role, String origin) {
        String canonical = CenterTrust.normalizeOrigin(origin);
        if (trust == null || canonical == null || !canonical.equals(origin) || !trust.covers(role, origin))
            throw new OriginRejected();
        List<String> pins = trust.pins(role);
        if (pins.size() < CenterTrust.MIN_PINS) throw new PinMissing();
        // The pin set is part of the cache key: a rotation to a new pin must not
        // reuse the previous pinner for the same origin.
        return PINNED.computeIfAbsent(role + "|" + origin + "|" + String.join(",", pins), key -> {
            CertificatePinner.Builder pinner = new CertificatePinner.Builder();
            URI uri = URI.create(origin);
            String host = uri.getHost();
            for (String pin : pins) pinner.add(host, pin);
            return BASE.newBuilder()
                    .certificatePinner(pinner.build())
                    .build();
        });
    }

    /** Result of resolving the best client available for a role and origin. */
    public static final class Endpoint {
        private final OkHttpClient client;
        private final boolean pinned;

        private Endpoint(OkHttpClient client, boolean pinned) {
            this.client = client;
            this.pinned = pinned;
        }

        public OkHttpClient client() {
            return client;
        }

        /** True only when a trusted description covered the origin and pins were applied. */
        public boolean isPinned() {
            return pinned;
        }
    }

    /**
     * Pinned client when the store covers this role and origin; otherwise the shared
     * unpinned client. Callers that must not proceed unpinned (the new center-assisted
     * signaling path) use {@link #pinned} instead and fail closed.
     */
    public static Endpoint endpoint(CenterTrustStore store, String role, String origin) {
        CenterTrust trust = store == null ? null : store.effective();
        if (trust != null && trust.covers(role, origin)) {
            try {
                return new Endpoint(pinned(trust, role, origin), true);
            } catch (SecurityException ignored) {
                // Fall through to the unpinned client; the caller decides whether
                // that is acceptable for the operation it is performing.
            }
        }
        return new Endpoint(BASE, false);
    }

    /**
     * Exact-origin request target. The origin must be canonical https with no
     * userinfo, query or fragment; the path must be an absolute in-origin path.
     */
    public static String targetUrl(String origin, String pathAndQuery) {
        String canonical = CenterTrust.normalizeOrigin(origin);
        if (canonical == null || !canonical.equals(origin)) throw new OriginRejected();
        if (pathAndQuery == null || !pathAndQuery.startsWith("/") || pathAndQuery.startsWith("//")
                || pathAndQuery.contains("://") || pathAndQuery.contains("#"))
            throw new OriginRejected();
        try {
            URI target = URI.create(canonical + pathAndQuery);
            if (!"https".equals(target.getScheme()) || target.getHost() == null
                    || target.getRawUserInfo() != null || target.getRawFragment() != null
                    || !"https".equalsIgnoreCase(target.getScheme()))
                throw new OriginRejected();
            // The authority must still be exactly the pinned origin.
            if (!canonical.equals(originOf(target))) throw new OriginRejected();
            return target.toASCIIString();
        } catch (OriginRejected rejected) {
            throw rejected;
        } catch (Exception error) {
            throw new OriginRejected();
        }
    }

    /**
     * Split an absolute request URL into the canonical origin and its in-origin
     * path+query. Rejects userinfo and fragments, so the pin decision is made on the
     * exact scheme/host/port that will actually be contacted.
     */
    public static String[] splitTarget(String url) {
        if (url == null || url.length() > 8192) throw new OriginRejected();
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception error) {
            throw new OriginRejected();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new OriginRejected();
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw new OriginRejected();
        String origin = originOf(uri);
        if (origin == null) throw new OriginRejected();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = uri.getRawQuery();
        return new String[]{origin, query == null ? path : path + "?" + query};
    }

    private static String originOf(URI uri) {
        try {
            String host = uri.getHost();
            if (host == null) return null;
            return new URI("https", null, host.toLowerCase(java.util.Locale.ROOT), uri.getPort(), null, null, null)
                    .toASCIIString();
        } catch (Exception error) {
            return null;
        }
    }

    /** Bounded HTTP result. No URL, header or pin is retained beyond what callers need. */
    public static final class Result {
        public final int status;
        public final String body;
        public final String setCookie;
        public final String retryAfter;

        Result(int status, String body, String setCookie, String retryAfter) {
            this.status = status;
            this.body = body;
            this.setCookie = setCookie;
            this.retryAfter = retryAfter;
        }
    }

    /**
     * Perform one JSON request. TLS pin failures are translated to a fixed code and
     * the original exception is discarded so a stack trace cannot carry the host.
     */
    public static Result exchange(OkHttpClient client, String method, String url, String jsonBody,
                                  Map<String, String> headers, int connectTimeoutSeconds,
                                  int readTimeoutSeconds) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).method(method,
                jsonBody == null ? null : RequestBody.create(jsonBody, JSON_MEDIA_TYPE));
        if (headers != null) for (Map.Entry<String, String> header : headers.entrySet())
            if (header.getValue() != null) builder.header(header.getKey(), header.getValue());
        Request request = builder.build();
        try (Response response = timed(client, connectTimeoutSeconds, readTimeoutSeconds)
                .newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : BoundedHttpReader.readUtf8(body.byteStream(), MAX_BODY_BYTES);
            return new Result(response.code(), text, response.header("Set-Cookie"), response.header("Retry-After"));
        } catch (SSLPeerUnverifiedException pinFailure) {
            throw new SecurityException(TLS_PIN_MISMATCH);
        }
    }

    /**
     * Timeout variants derived from a shared client: {@code newBuilder} keeps the same
     * dispatcher, connection pool and threads, so no extra client or thread is leaked.
     *
     * <p>Keyed on object identity rather than a hash of it. A collision here would hand
     * a caller a client built for a different pin set, and OkHttp performs no pin check
     * for a host its pinner does not know, so that would silently send the request
     * unpinned.
     */
    private static synchronized OkHttpClient timed(OkHttpClient client, int connectSeconds, int readSeconds) {
        Map<String, OkHttpClient> variants = TIMED.computeIfAbsent(client, ignored -> new HashMap<>());
        String key = connectSeconds + "|" + readSeconds;
        OkHttpClient cached = variants.get(key);
        if (cached != null) return cached;
        OkHttpClient derived = client.newBuilder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .build();
        variants.put(key, derived);
        return derived;
    }

    private static final Map<OkHttpClient, Map<String, OkHttpClient>> TIMED = new IdentityHashMap<>();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /** Read a bounded body for callers that already hold an InputStream. */
    static String readBounded(InputStream stream) throws IOException {
        return BoundedHttpReader.readUtf8(stream, MAX_BODY_BYTES);
    }
}
