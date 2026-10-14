# Peer protocol and security boundary

Version 1 is preview-only. Independent application ID, signing identity and stores
prevent test installation from migrating the stable app's Enrollment or Vault.

## Trust and cryptography

- Long-term P-256 signing key: Android Keystore alias `simhub-p2p-identity-v1`.
- Peer ID: base64url SHA-256 of DER SubjectPublicKeyInfo.
- Double QR: signed node offer, signed controller response; ephemeral P-256 ECDH.
- Transcript binds session ID, expiry, both identity and ephemeral public keys,
  and the exact permission set. HKDF-SHA256 domain `simhub-peer-v1` derives a
  per-pair content root. The root is Keystore-wrapped, separate from all Relay keys.
- Direct TCP: authenticated encrypted hello with fresh ECDH on every RPC,
  ephemeral session key bound to identities and hello ID, AES-256-GCM frames.
- WebRTC: authenticated encrypted SDP/candidates, built-in ICE/DTLS and additional
  application-layer AES-256-GCM. The bridge only executes bundled HTML and only
  accepts messages from the exact appassets main-frame origin.
- Mailbox: pair content root encrypts short-lived frames. This provides content
  confidentiality and identity binding, but retained ciphertext has no ratchet
  forward secrecy if that root is later compromised. Never claim otherwise.

Every frame binds protocol domain, sender, recipient, request ID and expiry.
Frame limits, socket timeouts, bounded workers and queue quotas apply before SMS
provider access. Source/destination switches and wrong domains fail closed.

## Authorization and exactly-once boundary

Peer profile separates local role, permissions, pair state, connection policy,
connection-service binding and actual transport. P2P does not grant Legacy Relay
permission. The service separately authenticates each identity by signed nonce.

`read`, `history`, `state`, `send` grants are explicit; `send` defaults off.
Every SMS send also passes Android role/permission, Channel ID/Revision and rate
limit checks. A durable receipt is claimed before calling SmsManager. Reusing a
request ID with changed payload fails; a crash after claim returns indeterminate
and does not blindly retry. This is at-most-once dispatch, not guaranteed
exactly-once delivery by the mobile network.

Direct, WebRTC and mailbox route attempts retain the same logical command ID.
P2P callback IDs are excluded from the Legacy Relay command-ACK queue.
Events are encrypted per peer in a separate durable SQLite queue; ACK deletion
is scoped to that peer. Provider history has its own descending `(date,_id)`
cursor and never advances the existing Relay history cursor.

## Services and metadata

`/api/v2/p2p/` is a separate process and database. It can be routed alongside an
existing Relay domain; it cannot read Global Vault, Enrollment or Node Key.
The public service operator is not implemented by a domain placeholder.
STUN/TURN URLs and TURN shared secret must be deliberately configured.

Directory entries include only explicitly registered relationships. An unseen
P2P-only device cannot be shown by a server. `activeTransport` is a client's
reported state, not server verification of network path.

Registration needs proof of possession, short-lived challenge and rate limiting.
Mailbox requires both registered peers' fallback consent. Frames have TTL, byte
and count quotas; tokens expire and rotate. TURN credentials expire in five
minutes. TLS termination is mandatory outside the localhost sandbox.
Server metadata includes public-key IDs, counterpart IDs, connection IP/time,
byte count and transport reports. No content decryption keys are accepted.

## Preview limitations

There is no operated public endpoint, external security audit, real-SIM/OEM
certification or application-store approval. QR authority depends on the user
physically selecting the intended two phones. Pair root rotation and recovery
require a fresh pairing; multi-controller key distribution is not a shared Vault.
Background operation depends on user-enabled foreground service and Android/OEM
policy. Revocation is locally enforced immediately; offline UI on the other
phone may remain stale until reconnect.
