# Android Peer: connection and release acceptance (Dev only)

## Architecture boundaries

- Business RPC: `PeerRpc` and `PeerProtocol` remain independent of the selected transport.
- Direct transports: LAN TCP and WebRTC data-only RTC. The content root stays on paired devices.
- Auxiliary signaling: the authenticated enrolled center carries only separately encrypted
  SDP/ICE. Node requests use the **node** HTTPS origin and device token;
  controller requests use the independently pinned **admin** HTTPS origin and admin session.
- Optional coturn: TURN is a **packet relay**, not direct P2P. It never becomes the
  SIM Hub durable message Relay or receives a business content key.
- Legacy Relay authorization remains independent. The transport router must not convert a
  pair into a Relay enrollment, confer send grants, or resubmit an uncertain SMS command.

## Operator-provided signaling center

Remote WebRTC requires both devices to reach the same trusted, publicly reachable
SimHub signaling center. Offline double-scan alone does not carry Internet signaling.
The node must already be enrolled there and the controller must have an independently
authenticated and authorized profile on that center. The center description must pin
both the node and admin origins and at least two SPKI keys per role.

User pairing:

1. Start the node's user-authorized Peer service. Scan the signed node QR from the
   controller, then scan the controller's response on the node.
2. On the node grant center-assisted signaling, wait for the binding, then display the
   node-signed signaling binding QR.
3. On the controller import the signed binding QR; verify identity and trusted center.
4. Keep both Peer services active and check **actual data channel online**, not only
   successful signaling. Try state, history, and an authorized test SMS on real SIMs.

## Optional TURN (hard NAT fallback)

The signaling center may set:

```dotenv
SIMHUB_PEER_TURN_URLS=turn:turn.example.invalid:3478?transport=udp,turns:turn.example.invalid:5349?transport=tcp
SIMHUB_PEER_TURN_SECRET=<independent-random-secret-at-least-32-chars>
```

Use exactly the same secret in the operator-owned coturn
`static-auth-secret`; configure `use-auth-secret`, realm, TLS, network ports,
denied peer networks, concurrent allocations and byte quotas.

A controller creating an authenticated session and its node joining the signed
binding may each receive different **10-minute** TURN REST credentials.
The credentials are never persisted to the SimHub database or included in SDP/ICE
messages. The receiving app validates the endpoint/TTL and ignores credentials
unless its *local* pair explicitly opted into TURN; both parties need to opt in.
TURN cannot be automatically activated by node enrollment, center configuration,
remote signaling consent, or legacy Relay linkage.

Real NAT traversal still depends on the operator's reachable TURN infrastructure,
firewall configuration, and mobile OS lifecycle. No universal network-reachability
claim is made when no center or TURN exists. The center sees connection metadata;
TURN sees IP and packet metadata, but not decrypted SMS content.

## Tests and release gates

- Center trust: distinct node/admin origins; reject untrusted signed-node origin,
  missing controller pin set and incorrect role.
- Network policy: unauthenticated TURN disabled; legacy Relay and TURN use
  different permissions; no direct route under Relay-only mode.
- Server: coturn REST HMAC/expiry and invalid URLs, authenticated session-only
  credential issue; old sessions cannot reuse ICE/short-lived credentials.
- Real Android: pairing on LAN; mobile-data ↔ another carrier; CGNAT ↔ CGNAT;
  TURN-only under deliberately restricted NAT; Wi-Fi/LTE handover; screen off,
  Doze and app force-stop; identity revocation and KeyStore recovery; send
  idempotence after a dropped ACK; history >1000 records.
- CI alone does not prove these real-network tests. Production signing and a
  Stable release are blocked until real-world P2P validation and security review
  are recorded for the exact commit.

Stable `main` must not contain this Dev module. Promotion should cherry-pick
reviewed **individual modules**, their migrations and tests, never merge all of
`develop` into Stable.
