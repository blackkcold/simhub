package com.blackkcold.simhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Exercises the pinning behavior of the actual production client factory against a
 * real TLS handshake.
 *
 * <p>The test adds a test-only trust anchor on top of the pinned client so a
 * self-signed test certificate can complete a handshake. Production never widens the
 * platform trust store; the pin under test is the one {@link SecureHttp} builds.
 */
public class SecureHttpTrustTest {
    private HttpsTestServer server;
    private Path certPem;
    private Path backupCertPem;
    private String correctPin;
    private String wrongPin;

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/center-test/" + name).toURI());
    }

    @Before
    public void setUp() throws Exception {
        certPem = resource("center-test-cert.pem");
        backupCertPem = resource("center-test-backup-cert.pem");
        server = new HttpsTestServer(certPem, resource("center-test-key.pem"));
        correctPin = CenterTrust.pinFor(certificate(certPem).getPublicKey().getEncoded());
        wrongPin = CenterTrust.pinFor(certificate(backupCertPem).getPublicKey().getEncoded());
        assertFalse(correctPin.equals(wrongPin));
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private static X509Certificate certificate(Path pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(java.nio.file.Files.newInputStream(pem));
    }

    /** The pinned client plus a test-only anchor; production leaves the anchor alone. */
    private OkHttpClient client(CenterTrust trust) throws Exception {
        return anchor(SecureHttp.pinned(trust, CenterTrust.ROLE_NODE, server.origin()));
    }

    /** Adds the test-only trust anchor to an already pinned client. */
    private OkHttpClient anchor(OkHttpClient client) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, HttpsTestServer.trustFor(certPem), new SecureRandom());
        X509TrustManager trustManager = (X509TrustManager) HttpsTestServer.trustFor(certPem)[0];
        return client.newBuilder()
                .sslSocketFactory(context.getSocketFactory(), trustManager)
                .build();
    }

    private CenterTrust trust(String... pins) {
        List<String> values = List.of(pins);
        return CenterTrust.of("abcdefghijklmnop0123456789", server.origin(), values,
                "https://admin.relay.invalid", List.of(pinBytes(3), pinBytes(4)));
    }

    private static String pinBytes(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return "sha256/" + java.util.Base64.getEncoder().encodeToString(value);
    }

    @Test
    public void acceptsTheExactOriginWithTheConfiguredPin() throws Exception {
        CenterTrust trust = trust(correctPin, wrongPin);
        SecureHttp.Result result = SecureHttp.exchange(client(trust), "GET",
                SecureHttp.targetUrl(server.origin(), "/api/v1/devices/x/lifecycle"),
                null, Map.of("Accept", "application/json"), 5, 5);
        assertEquals(200, result.status);
        assertEquals(1, server.receivedRequests().size());
    }

    @Test
    public void rejectsAValidChainWithTheWrongPinBeforeSendingAnything() throws Exception {
        CenterTrust trust = trust(wrongPin, pinBytes(5));
        SecurityException failure = assertThrows(SecurityException.class, () -> SecureHttp.exchange(
                client(trust), "POST",
                SecureHttp.targetUrl(server.origin(), "/api/v1/devices/x/events"),
                "{\"secret\":true}", Map.of("Authorization", "Device super-secret-token"), 5, 5));
        assertEquals(SecureHttp.TLS_PIN_MISMATCH, failure.getMessage());
        assertTrue("no request may reach the server", server.receivedRequests().isEmpty());
    }

    @Test
    public void refusesAnOriginThatTheDescriptionDoesNotCover() {
        CenterTrust trust = trust(correctPin, wrongPin);
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.pinned(trust, CenterTrust.ROLE_NODE, "https://other.relay.invalid"));
        // targetUrl is a pure origin gate; a non-canonical origin never becomes a target.
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.targetUrl("https://other.relay.invalid/", "/api/v1/x"));
    }

    @Test
    public void refusesAnEmptyOrSinglePinSet() {
        CenterTrust single = CenterTrust.of("abcdefghijklmnop0123456789", server.origin(),
                List.of(correctPin), "https://admin.relay.invalid", List.of(pinBytes(3), pinBytes(4)));
        assertThrows(SecureHttp.PinMissing.class,
                () -> SecureHttp.pinned(single, CenterTrust.ROLE_NODE, server.origin()));
    }

    @Test
    public void neverFollowsARedirectToAnotherOrigin() throws Exception {
        server.redirectTo("https://elsewhere.relay.invalid/api/v1/steal");
        CenterTrust trust = trust(correctPin, wrongPin);
        SecureHttp.Result result = SecureHttp.exchange(client(trust), "GET",
                SecureHttp.targetUrl(server.origin(), "/api/v1/devices/x/lifecycle"),
                null, Map.of("Cookie", "simhub_session=secret"), 5, 5);
        assertEquals(302, result.status);
        assertEquals(1, server.receivedRequests().size());
        assertFalse(server.receivedRequests().get(0).contains("elsewhere.relay.invalid"));
    }

    @Test
    public void rejectsRequestTargetsThatEscapeTheOrigin() {
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.targetUrl(server.origin(), "//other.relay.invalid/api/v1/x"));
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.targetUrl(server.origin(), "api/v1/x"));
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.targetUrl(server.origin(), "/api/v1/x#frag"));
        assertThrows(SecureHttp.OriginRejected.class,
                () -> SecureHttp.targetUrl("https://user@node.relay.invalid", "/api/v1/x"));
        assertEquals(server.origin() + "/api/v1/devices/x/commands/pending?limit=50&wait=15",
                SecureHttp.targetUrl(server.origin(), "/api/v1/devices/x/commands/pending?limit=50&wait=15"));
    }

    @Test
    public void splitsATargetIntoAnExactOriginPlusPath() {
        String[] parts = SecureHttp.splitTarget(server.origin() + "/api/v1/devices/abc/state?once=1");
        assertEquals(server.origin(), parts[0]);
        assertEquals("/api/v1/devices/abc/state?once=1", parts[1]);
        assertThrows(SecureHttp.OriginRejected.class, () -> SecureHttp.splitTarget("http://node.relay.invalid/x"));
        assertThrows(SecureHttp.OriginRejected.class, () -> SecureHttp.splitTarget("https://u@node.relay.invalid/x"));
        assertThrows(SecureHttp.OriginRejected.class, () -> SecureHttp.splitTarget("https://node.relay.invalid/x#f"));
    }

    /**
     * The pinned-client cache is keyed on the pin set, not just role and origin: after a
     * key rotation a request to the same origin must not be answered by the client that
     * still carries the retired pins.
     */
    @Test
    public void keysThePinnedClientOnThePinSetSoRotationCannotReuseTheOldPinner() throws Exception {
        CenterTrust before = trust(correctPin, wrongPin);
        CenterTrust after = trust(pinBytes(6), pinBytes(7));
        OkHttpClient pinnedBefore = SecureHttp.pinned(before, CenterTrust.ROLE_NODE, server.origin());
        OkHttpClient pinnedAfter = SecureHttp.pinned(after, CenterTrust.ROLE_NODE, server.origin());
        assertNotSame("a rotated pin set must not reuse the previous pinner", pinnedBefore, pinnedAfter);
        assertSame(SecureHttp.pinned(before, CenterTrust.ROLE_NODE, server.origin()), pinnedBefore);
        // And the rotated client really enforces the new set against the live server.
        SecurityException failure = assertThrows(SecurityException.class, () -> SecureHttp.exchange(
                anchor(pinnedAfter), "GET",
                SecureHttp.targetUrl(server.origin(), "/api/v1/devices/x/lifecycle"),
                null, Map.of(), 5, 5));
        assertEquals(SecureHttp.TLS_PIN_MISMATCH, failure.getMessage());
        assertTrue(server.receivedRequests().isEmpty());
    }
}
