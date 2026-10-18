package com.blackkcold.simhub.p2p;

/** Pairing authorization is independent of the currently selected route. */
public enum ConnectionPolicy {
    P2P_ONLY, RELAY_ONLY, P2P_RELAY_FALLBACK;
    // Keep the old signature for legacy paths; it never confers TURN consent.
    public boolean permits(String transport,boolean fallbackConsent,boolean relayBound){
        return permits(transport,fallbackConsent,relayBound,false);
    }
    /** TURN consent and legacy Relay enrollment are independent permissions. */
    public boolean permits(String transport,boolean fallbackConsent,boolean relayBound,boolean turnConsent){
        return switch(transport){
            case "LAN","DIRECT" -> this!=RELAY_ONLY;
            case "TURN" -> this==P2P_RELAY_FALLBACK && turnConsent;
            case "MAILBOX" -> this==P2P_RELAY_FALLBACK && fallbackConsent && relayBound;
            case "LEGACY_RELAY" -> relayBound && (this==RELAY_ONLY || this==P2P_RELAY_FALLBACK);
            default -> false;
        };
    }
}
