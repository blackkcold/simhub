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
