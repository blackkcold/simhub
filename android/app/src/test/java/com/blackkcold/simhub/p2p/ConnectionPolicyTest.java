package com.blackkcold.simhub.p2p;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The transport cannot confer permission by choosing a different network route. */
public class ConnectionPolicyTest {
    @Test public void hybridUsesOnlyIndependentAuthorizedRelay() {
        assertTrue(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("LEGACY_RELAY", false, true));
        assertFalse(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("LEGACY_RELAY", true, false));
        assertFalse(ConnectionPolicy.P2P_ONLY.permits("LEGACY_RELAY", true, true));
        assertTrue(ConnectionPolicy.RELAY_ONLY.permits("LEGACY_RELAY", false, true));
        assertFalse(ConnectionPolicy.RELAY_ONLY.permits("LEGACY_RELAY", false, false));
    }
    @Test public void directDoesNotInheritRelayGrant() {
        assertTrue(ConnectionPolicy.P2P_ONLY.permits("LAN", false, false));
        assertTrue(ConnectionPolicy.P2P_ONLY.permits("DIRECT", false, false));
        assertTrue(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("DIRECT", false, false));
        assertFalse(ConnectionPolicy.RELAY_ONLY.permits("DIRECT", true, true));
        assertFalse(ConnectionPolicy.P2P_ONLY.permits("TURN", true, true));
        assertFalse(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN", false, true));
        assertFalse(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN", true, false));
        assertTrue(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN", true, true));
        assertFalse(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("UNKNOWN", true, true));
    }
}
