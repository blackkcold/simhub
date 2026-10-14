# Automatic public P2P preview

## UI / connection audit changes

The Android preview now reuses SIM Hub's dual-role theme, with role-based pairing, device cards, a scoped SMS view, and per-device connection detail instead of three duplicated navigation tabs. Signaling READY is not an authenticated data channel. Private host ICE pairs can report LAN; otherwise a direct P2P channel does not automatically prove cross-internet connectivity.

The Relay route is never silently attached during a P2P request. Explicit association verifies the node's published P2P identity and an independently authorized Relay, even when direct P2P is temporarily offline. SMS send and retry actions use one persisted logical ID and an indeterminate result never silently creates a new send. The Web Relay UI does not claim independent P2P permissions.

Public two-runner DIRECT, local preview, and Android sandbox checks must pass before the isolated APK is attached to existing draft PR #84. The preview must not merge into main without separate explicit approval.

Branch: `feature/p2p-isolated-preview`; draft PR #84. Never merge or enable auto-merge without a new user instruction. Main 0.17.1 is the imported baseline; the preview has a separate application ID and disables stable self-update.

Double QR pairing creates a separate peer identity, content root and explicit read/history/state/send grants. New pairings automatically enable public discovery. No user hostname, port forwarding, Relay enrollment or Vault is required for public direct attempts.

`PeerCloudConnector` connects to the existing PeerJS Cloud signaling service over WSS. `PeerCloudProtocol` derives a different opaque UUID for each pairing/direction and puts only encrypted SDP/ICE in the supported data-offer metadata envelope. Google and Cloudflare STUN discover candidates. Actual RPC/SMS travels through authenticated TCP or WebRTC data channels, never through public signaling. Default configuration contains no TURN servers or content mailbox.

`PeerService` owns discovery and the bundled WebView WebRTC engine, reconnects after network changes and keeps running as a user-enabled foreground service. ICE stats must confirm the selected candidate pair before the bridge handles data. Old callbacks cannot mark a newer connection ready. This implementation uses Android WebView WebRTC, not an org.webrtc native SDK.

`PeerHubGateway` is shared by the P2P page and Android Relay workspace. An independently authorized, identity-matched Relay is tried first; TCP/public P2P is the backup. Relay transports a doubly encrypted `peer.rpc` command. Both carrier authentication and the P2P grant are checked. A P2P pairing cannot enroll a node or unlock a Vault. Without configured Relay, failed direct attempts fail without hidden forwarding.

SMS attempts preserve one logical ID, original payload and expiry across routes. Separate encrypted outbox and durable peer receipts prevent repeated dispatch after an uncertain response. Channel identity/revision, Android SMS policy and rate limits are checked at the node. Read-only pairing cannot become send-capable through a Relay carrier. Management commands outside the P2P RPC grant remain Relay-only.

Verification lives in `.github/workflows/p2p-preview.yml`: two independent public runners must exchange 128 KB over STUN-only DIRECT with different public endpoints and no forwarded host candidates, before the Android sandbox is allowed to run. The sandbox exercises production Keystore, double QR, TCP, revocation, independent Relay carrier authorization, route policy, duplicate SMS receipts and 1,205-row history. The exact emulator-installed APK is exported with the results and checksum. Standard CI, CodeQL and OTA checks remain enabled.

The bundled public services are third-party infrastructure. They are not SIM Hub-operated services. NAT/firewall restrictions can defeat pure direct connectivity; no universal connection guarantee is claimed. Real SIM delivery, OEM background behavior and store approval require external acceptance.
