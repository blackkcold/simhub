# Peer protocol and independent authorization

## Pairing and frames

Long-term P-256 signing identities use Android Keystore alias `simhub-p2p-identity-v1`. IDs are base64url SHA-256 of DER public keys. Signed double QR and ephemeral ECDH bind the session, expiry, both identities/ephemeral keys and the exact permissions. HKDF-SHA256 derives a pair-specific content root, independently Keystore-wrapped from Enrollment, Node Key and Global Vault.

TCP uses an authenticated fresh ECDH hello for each RPC. WebRTC uses DTLS plus application AES-256-GCM. Frames bind domain, sender, recipient, logical request ID and expiry. Bounded frame sizes, timeouts and worker/queue quotas apply. The bridge accepts only the bundled appassets main-frame origin.

## Default public discovery

The WSS endpoint is `wss://0.peerjs.com/peerjs`; public STUN endpoints are Google `stun:stun.l.google.com:19302` and Cloudflare `stun:stun.cloudflare.com:3478`. No user configuration is required. QR `networkMode: PUBLIC_DIRECT_V1` advertises consent to discovery; old pairings require explicitly enabling it.

Room IDs use pair-root HKDF with salt `simhub-discovery-v1`, info `simhub-peer-v1|peerjs-room|<identity>`, then an opaque UUID with RFC UUID version/variant bits. Every pair has separate directional rooms.

PeerJS Cloud requires a normal data OFFER envelope. The outer payload contains constant placeholder SDP (`v=0`), data-channel labels and `metadata.simhub` carrying the authenticated encrypted real signal. Encrypted signal actions contain only OFFER/ANSWER/CANDIDATE. They cannot carry SMS, RPC or arbitrary application content. Receivers verify the exact source/destination rooms, frame authentication and signal type; replay IDs are bounded and remembered. The placeholder SDP never participates in the actual RTC connection.

Signaling READY and transport DIRECT are distinct states. ICE selected-pair confirmation precedes data processing. Delayed stats are retried briefly; old connection callbacks are ignored. Disconnects permit a fresh connection; network changes reconnect discovery. No default TURN, HTTPS content relay or mailbox is used.

PeerJS/STUN operators can observe connection IP, timing, opaque room IDs, packet size and STUN mapping. PeerJS receives encrypted real SDP/ICE, not plaintext fingerprints or candidate IPs. The remote paired peer necessarily learns its counterpart's usable ICE candidates. Pair-root encryption of signaling/Relay-carried frames does not supply a ratchet or forward secrecy after root compromise.

## Optional existing Relay

A signed QR/state hint contains only Relay origin/device ID, never credentials. Association requires an already authorized local Relay controller, any required local unlock, and Relay state advertising the same P2P identity. The user can cancel the association. Relay authorization remains separate from P2P grants.

When configured and authorized, Relay is preferred. `peer.rpc` wraps a P2P AEAD request in the existing Node Key encrypted command. Carrier IDs use an independent `pw-` digest namespace; inner logical IDs are preserved on direct retries. Device-authenticated POST stores a bounded short-lived ciphertext response; admin-authenticated GET reads it. Responses are immutable, request-digest bound and scoped to device/command; reset/revocation rejects access. The server neither accepts content keys nor exposes responses in ordinary command history.

Deploy the feature branch's compatible Relay server for this optional carrier. An older server may reject `peer.rpc`, allowing direct P2P to be attempted. Public P2P does not require deploying any server. The historical independent `peer_connect.py`/TURN/mailbox source remains available for compatibility tests and is not the default flow.

## Grants and receipts

Explicit grants are `read`, `history`, `state` and optional `send` (off by default). Every send also needs the device's SMS role/permission, matching Channel ID/Revision and rate budget. An atomic durable receipt precedes dispatch. A reused ID with changed payload is rejected; a crash after claim returns indeterminate and never blindly resends. A known receipt remains authoritative after request expiry. This is at-most-once dispatch, not guaranteed mobile-network delivery.

Outbound requests are encrypted and keep their original expiry. Unconfirmed expired submissions require status resolution, not another send. Callback command IDs are excluded from Legacy Relay ACKs. Events and ACKs are peer-scoped; history uses a separate descending `(date,_id)` cursor, leaving Relay history untouched. Revocation deletes the peer key/outbox/events; stale callbacks cannot restore the grant. Re-pairing creates fresh content keys.

A Relay directory lists only enrolled devices. Reported P2P backup capability cannot reveal an unregistered P2P-only device, grant a browser permission or prove a live route.

References: [PeerServer Cloud](https://peerjs.com/server/cloud), [ICE RFC 8445](https://www.rfc-editor.org/rfc/rfc8445).
