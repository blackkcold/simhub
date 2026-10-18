package com.blackkcold.simhub.p2p;

/** Pairing authorization is independent of the currently selected route. */
public enum ConnectionPolicy {
    P2P_ONLY, RELAY_ONLY, P2P_RELAY_FALLBACK;
    public boolean permits(String transport,boolean consent,boolean relayBound){
        return switch(transport){
            case "LAN","DIRECT" -> this!=RELAY_ONLY;
            case "TURN","MAILBOX" -> this==P2P_RELAY_FALLBACK && consent && relayBound;
            case "LEGACY_RELAY" -> relayBound && (this==RELAY_ONLY || this==P2P_RELAY_FALLBACK);
            default -> false;
        };
    }
}
