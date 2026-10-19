package com.blackkcold.simhub.p2p;

import org.junit.Test;
import org.json.JSONObject;
import java.security.KeyPair;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PeerCoreContractTest {
    @Test public void protocolAndIdentityAreRouteIndependent() throws Exception {
        KeyPair device = PeerCrypto.ephemeral(), controller = PeerCrypto.ephemeral();
        byte[] sharedA = PeerCrypto.dh(device.getPrivate(), controller.getPublic());
        byte[] sharedB = PeerCrypto.dh(controller.getPrivate(), device.getPublic());
        assertArrayEquals(sharedA, sharedB);
        byte[] root = PeerCrypto.derive(sharedA, PeerCrypto.utf8("fixed-test-salt"), "content-root");
        String from = PeerCrypto.digest(device.getPublic().getEncoded());
        String to = PeerCrypto.digest(controller.getPublic().getEncoded());
        String id = "explicitRequestID0000123";
        long expires = 1800000120L;
        JSONObject frame = PeerProtocol.seal(root, from, to, id, expires,
                new JSONObject().put("action", "device.state"));
        assertEquals("device.state", PeerProtocol.open(root, from, to, frame, expires - 10)
                .getString("action"));
        assertThrows(SecurityException.class,
                () -> PeerProtocol.open(root, to, from, frame, expires - 10));
        byte[] signalKey = PeerSignalingProtocol.deriveKey(root);
        assertFalse(Arrays.equals(root, signalKey));
        Arrays.fill(root, (byte)0);
        Arrays.fill(sharedA, (byte)0);
        Arrays.fill(sharedB, (byte)0);
        Arrays.fill(signalKey, (byte)0);
    }

    @Test public void acceptsOnlyWellScopedCoturnRestCredentials() {
        long now = 1700000000L;
        assertTrue(TurnIceCredentialPolicy.allowsUrl("turn:turn.example.org:3478?transport=udp"));
        assertTrue(TurnIceCredentialPolicy.allowsUrl("turns:turn.example.org:5349?transport=tcp"));
        assertFalse(TurnIceCredentialPolicy.allowsUrl("https://untrusted.invalid"));
        assertFalse(TurnIceCredentialPolicy.allowsUrl("turn:user@host.invalid"));
        assertFalse(TurnIceCredentialPolicy.allowsUrl("turn:host.invalid/route"));
        assertFalse(TurnIceCredentialPolicy.allowsUrl("turn:host.invalid?transport=invalid"));
        assertTrue(TurnIceCredentialPolicy.allowsCredentials(
                "1700000600:0123456789abcdef0123456789abcdef",
                "B0Mq+R1KJkDerHMmygIlTzYKDkA=", now));
        assertFalse(TurnIceCredentialPolicy.allowsCredentials(
                "1700000600:0123456789abcdef0123456789abcdef",
                "B0Mq+R1KJkDerHMmygIlTzYKDkA=", 1700000600L));
        assertFalse(TurnIceCredentialPolicy.allowsCredentials(
                "1700000600:0123456789abcdef0123456789abcdef",
                "B0Mq+R1KJkDerHMmygIlTzYKDkA=", 1699999000L));
        assertFalse(TurnIceCredentialPolicy.allowsCredentials(
                "1700000600:0123456789abcdef0123456789abcdef",
                "not-base64", now));
    }

    @Test public void deniedNetworkNeverGrantsBusinessPermission() {
        for (ConnectionPolicy policy : ConnectionPolicy.values()) {
            assertFalse(policy.permits("TURN", true, true));
            assertFalse(policy.permits("UNKNOWN", true, true, true));
        }
        assertTrue(ConnectionPolicy.P2P_ONLY.permits("DIRECT", false, false));
        assertFalse(ConnectionPolicy.P2P_ONLY.permits("LEGACY_RELAY", true, true));
        assertTrue(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("LEGACY_RELAY", false, true));
        assertTrue(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN", false, false, true));
        assertFalse(ConnectionPolicy.RELAY_ONLY.permits("TURN", true, true, true));
    }
}
