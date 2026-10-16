# Peer protocol and independent authorization

## Pairing and frames

Long-term P-256 signing identities use Android Keystore alias `simhub-p2p-identity-v1`. IDs are base64url SHA-256 of DER public keys. Signed double QR and ephemeral ECDH bind the session, expiry, both identities/ephemeral keys and the exact permissions. HKDF-SHA256 derives a pair-specific content root, independently Keystore-wrapped from Enrollment, Node Key and Global Vault.

TCP uses an authenticated fresh ECDH hello for each RPC. WebRTC uses DTLS plus application AES-256-GCM. Frames bind domain, sender, recipient, logical request ID and expiry. Bounded frame sizes, timeouts and worker/queue quotas apply.

## Native engine

The data channel is produced by `PeerNativeRtcTransport` on top of `io.github.webrtc-sdk:android` (pinned exact version). There is no WebView, no bundled script and no remote code load: the previous WebView engine, its assets and its JavaScript bridge are deleted. Data-only semantics are preserved — no audio or video track is created and no capture permission is requested. A single serial execution domain owns the engine, with an epoch counter that discards callbacks from a superseded connection, so `init`/`signal`/`restart`/`close` cannot leave a hanging future or duplicate RPC. LAN/DIRECT/TURN classification comes from the selected candidate pair in the real RTC statistics; an unknown route is never assumed to be DIRECT, and a TURN candidate is refused unless the route is separately authorized.

ICE configuration offers approved STUN only. The published defaults are Google `stun:stun.l.google.com:19302` and Cloudflare `stun:stun.cloudflare.com:3478`; a profile may carry an explicitly approved STUN list, and `turn:`/`turns:` entries are dropped rather than enabled. No TURN credential is embedded and no signaling response can add one.

## Center-auxiliary signaling

Signaling reuses the already registered center. Both roles keep their existing credentials (node Device token; controller admin session plus CSRF) and create a short-lived, pair-scoped session; the center never receives the pair content root and never carries business data.

- **Purpose separation.** `PeerSignalingProtocol` derives its own AES key with HKDF-SHA256 under the fixed label `simhub-peer-signal-v1` and its own outer domain. The business wire format (`PeerProtocol`, domain `simhub-peer-v1`) is unchanged, so a business ciphertext can never open as signaling and vice versa. Test vectors in `test_vectors/signaling-v1.json` are checked byte for byte from Python and Java.
- **Authenticated outer.** Version, domain, session, message id, direction, sequence, expiry, consent revision and nonce all enter the AEAD as authenticated data; only delivery metadata stays outside the ciphertext.
- **Admitted content.** The plaintext admits `OFFER`, `ANSWER`, `ICE`, `END_OF_CANDIDATES` and `CLOSE` only. RPC actions (`sms.send`, `history`, `peer.rpc`, …) are rejected by a strict parser. A signaling frame never enters `PeerRpc.dispatch`.
- **Node side.** The node registers one binding per allowed controller: it proves possession of its P2P key over a signed transcript and submits `allowedControllerPeerId`/`allowedControllerPublicKey`, `consentRevision` and a one-time challenge. The server enforces that a device always keeps the same node identity and that a node identity cannot move to another device.
- **Controller side.** The controller requests a binding-scoped challenge, builds a session with its own signed transcript and receives a one-time session token scoped to that session. Frame send/ack/close require that token in addition to the admin session.
- **Receiver-side checks.** Message id and sequence deduplication plus expiry validation happen on the receiver, not only on the server. A retransmission keeps the same id, sequence and nonce.
- **Limits.** 300 s session, 120 s message, 64 KiB frame, 32 unacked frames and 2 MiB per direction, 2 MiB per direction and 64 MiB globally, 2 concurrent sessions per node, 60 frames/minute/pair, 10 sessions/minute/node, 8 bindings/device. These budgets are independent of the `peer_connect.py` mailbox quotas.
- **Revocation.** Revoking a pairing, a registration, a controller grant or the signaling consent invalidates the binding's sessions and queued frames; the node re-checks the grant before delivering a polled frame, and the server expires everything anyway.

A signaling session being READY is not the data channel being online. Only the selected candidate pair makes the route DIRECT, and only a live data channel makes `request()` succeed. Signaling consent is a distinct per-pair flag that defaults to `false`; the retired `publicDiscoveryEnabled` flag never enables the new path. Without a trusted description for the exact origin, the auxiliary path reports `CENTER_SETUP_REQUIRED` and the LAN capability is left untouched.

The center visible to an observer learns the registration relationship, the bound public keys, IP addresses, timing, frame sizes and delivery metadata. It does not learn the pair content key, and this document does not claim the center is unaware of which pairs exist.

## Optional existing Relay

A signed QR/state hint contains only Relay origin/device ID, never credentials. Association requires an already authorized local Relay controller, any required local unlock, and Relay state advertising the same P2P identity. The user can cancel the association. Relay authorization remains separate from P2P grants.

When configured and authorized, Relay is preferred. `peer.rpc` wraps a P2P AEAD request in the existing Node Key encrypted command. Carrier IDs use an independent `pw-` digest namespace; inner logical IDs are preserved on direct retries. Device-authenticated POST stores a bounded short-lived ciphertext response; admin-authenticated GET reads it. Responses are immutable, request-digest bound and scoped to device/command; reset/revocation rejects access. The server neither accepts content keys nor exposes responses in ordinary command history.

Deploy the feature branch's compatible Relay server for this optional carrier. An older server may reject `peer.rpc`, allowing direct P2P to be attempted. The `read`/`history`/`state`/`send` grants and the bounded encrypted signaling store are the only state the feature adds to the server.

## Trusted center description

`CenterTrust` is the only source of pinning. It carries, per role, one exact canonical `https://host[:port]` origin and at least two distinct SPKI SHA-256 pins. It arrives either as a generated configuration asset (controlled packaging) or as a versioned trusted QR description delivered out of band; a pairing hint and an enrolled address are never promoted to trust. Parsing rejects a non-HTTPS or non-canonical origin, a path/query/fragment/userinfo origin, fewer than two pins, duplicate pins, and a shared origin whose two roles disagree on the pin set. A description may name one or both roles; an absent role stays unauthorized.

## Grants and receipts

Explicit grants are `read`, `history`, `state` and optional `send` (off by default). Every send also needs the device's SMS role/permission, matching Channel ID/Revision and rate budget. An atomic durable receipt precedes dispatch. A reused ID with changed payload is rejected; a crash after claim returns indeterminate and never blindly resends. A known receipt remains authoritative after request expiry. This is at-most-once dispatch, not guaranteed mobile-network delivery.

Outbound requests are encrypted and keep their original expiry. Unconfirmed expired submissions require status resolution, not another send. Callback command IDs are excluded from Legacy Relay ACKs. Events and ACKs are peer-scoped; history uses a separate descending `(date,_id)` cursor, leaving Relay history untouched. Revocation deletes the peer key/outbox/events; stale callbacks cannot restore the grant. Re-pairing creates fresh content keys.

A Relay directory lists only enrolled devices. Reported P2P backup capability cannot reveal an unregistered P2P-only device, grant a browser permission or prove a live route.

References: [ICE RFC 8445](https://www.rfc-editor.org/rfc/rfc8445), [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).
