# Isolated P2P preview

Initial baseline: cf36ffba5caf940c3529e9caade4c4890798872b (0.17.0).
Updated test baseline: main f3bcd706e17df0acb9ff8fd92bc22c482bd1a5e6 (0.17.1).
The main update is imported into the feature branch only.
Branch: feature/p2p-isolated-preview. Never merge or enable auto-merge without user instruction.

Implement a separate peer trust store, double QR pairing, encrypted direct RPC,
NSD discovery, peer history pagination and durable command receipts. Preserve
Relay Enrollment, Node Key, Global Vault and existing queues. Preview uses a
separate application ID and disables the stable updater.

Network services must be explicit and replaceable. P2P_ONLY never permits a
data relay; P2P_RELAY_FALLBACK requires per-peer consent. Signaling, TURN and
mailbox do not grant Legacy Relay authorization. The server only lists peers
explicitly registered with it. No public endpoint is invented or activated.

Acceptance: authenticated two-device transport, rejected tamper/replay/expiry,
revocation, at-most-once SMS dispatch across routes, Channel ID and Revision,
1000+ history rows, Relay regression, preview APK build and sandbox evidence.
Real SIM, OEM Doze, Play review and public service operation require separate
external evidence; do not mark them complete from unit tests.
