package com.blackkcold.simhub;

/**
 * Presentation policy for enrollment-scoped Relay status.
 * Hardware SIM status is independent of this lifecycle.
 */
public final class RelayUiState {
    public enum Phase { UNPAIRED, ACTIVE, RESET_PENDING, RECOVERY }

    public static Phase phase(boolean enrolled, boolean resetPending, boolean recoveryRequired) {
        if (resetPending) return recoveryRequired ? Phase.RECOVERY : Phase.RESET_PENDING;
        return enrolled ? Phase.ACTIVE : Phase.UNPAIRED;
    }

    public static boolean showTransportDiagnostics(Phase phase) {
        return phase == Phase.ACTIVE;
    }

    public static boolean canRefreshRelay(Phase phase) {
        return phase == Phase.ACTIVE;
    }

    private RelayUiState() {}
}
