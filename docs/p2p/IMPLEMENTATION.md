# Center-auxiliary P2P preview

## UI / connection audit changes

The Android preview reuses SIM Hub's dual-role theme, with role-based pairing, device cards, a scoped SMS view, and per-device connection detail instead of three duplicated navigation tabs. Signaling READY is not an authenticated data channel. Private host ICE pairs can report LAN; otherwise a direct P2P channel does not automatically prove cross-internet connectivity.

The Relay route is never silently attached during a P2P request. Explicit association verifies the node's published P2P identity and an independently authorized Relay, even when direct P2P is temporarily offline. SMS send and retry actions use one persisted logical ID and an indeterminate result never silently creates a new send. The Web Relay UI does not claim independent P2P permissions.

The connection screen shows whether the device is signed in to a center, whether the pair's node identity matches, and whether signaling is authorized. It never renders a real host into an exportable diagnostic; a missing trusted description shows `CENTER_SETUP_REQUIRED`.

Preview, packaging and Android sandbox checks must pass before the isolated APK is attached to the develop test line. The preview must not merge into main without separate explicit approval.

Branch: `develop`; experimental code stays outside Stable `main`. Never merge or enable auto-merge without a new user instruction. Stable and Dev have independent releases; the preview has a separate application ID and disables stable self-update.

The optional trusted-center bootstrap now lets a confirmed SIM node opt in and register a pair-specific binding; its short-lived, node-signed QR can be imported by the paired controller after identity, revision and exact trusted-origin checks. This is deliberately an additional scan when LAN cannot first deliver the authenticated `device.state` binding, not a claim that offline double-scan alone guarantees arbitrary internet reachability. All business content keys, TURN, and legacy Relay remain independently authorized. The node role can open connection diagnostics; send status distinguishes submission from carrier send/delivery, and pending SMS commands can be reconciled individually.\n\n## Pairing, transports and signaling

Double QR pairing creates a separate peer identity, content root and explicit read/history/state/send grants. It does **not** enable the auxiliary path: `signalingConsent` defaults to `false` and can only be granted by an explicit per-pair user action once a trusted center description exists. The retired `publicDiscoveryEnabled` flag is kept for audit as `publicDiscoveryRetired` and never starts a network path.

`PeerService` owns the LAN listener, discovery and the native engine, reconnects after network changes and keeps running as a user-enabled foreground service. The engine is `PeerNativeRtcTransport` over the pinned `io.github.webrtc-sdk:android` AAR; ICE statistics must confirm the selected candidate pair before data is handled, old callbacks cannot mark a newer connection ready, and no WebView or bundled script exists any more.

`PeerCenterConnector` carries the signaling. The node uses its Device token through `ApiClient`; the controller uses `ControllerSecureClient` plus the one-time session token. Both create a pair-scoped session on the already registered center and exchange only end-to-end encrypted SDP/ICE. `PeerCenterProtocol` offers approved STUN only — no TURN credential, and no signaling response can add one.

`PeerHubGateway` is shared by the P2P page and Android Relay workspace. An independently authorized, identity-matched Relay is tried first; TCP/center-assisted P2P is the backup. Relay transports a doubly encrypted `peer.rpc` command. Both carrier authentication and the P2P grant are checked. A P2P pairing cannot enroll a node or unlock a Vault. Without configured Relay, failed direct attempts fail without hidden forwarding.

SMS attempts preserve one logical ID, original payload and expiry across routes. Separate encrypted outbox and durable peer receipts prevent repeated dispatch after an uncertain response. Channel identity/revision, Android SMS policy and rate limits are checked at the node. Read-only pairing cannot become send-capable through a Relay carrier. Management commands outside the P2P RPC grant remain Relay-only.

## Shared pinned HTTP client

`SecureHttp` is the single client behind every JSON request that carries a center credential, for both roles. It owns one base `OkHttpClient` (shared dispatcher, pool and threads) and derives pinned clients from it:

- exact canonical origin enforcement before any request is built, with userinfo, query and fragment rejected;
- `followRedirects(false)` and `followSslRedirects(false)`, so a 3xx is returned to the caller and a bearer can never move to another host;
- at least two distinct SPKI SHA-256 pins per role, and a fail-closed refusal when no pin matches the origin;
- TLS pin failures surface as the fixed code `CENTER_TLS_PIN_MISMATCH`, and a missing or unusable session as `CENTER_AUTH_REQUIRED`.

`ApiClient.raw()`, its pairing/enrollment entry points and `ControllerSecureClient.request()` were migrated from `HttpURLConnection` to this factory; the upper-layer method contracts, node token, controller cookie, CSRF, timeouts, response size cap, error codes and token rotation behaviour are unchanged.

## Configuration injection

The trusted description is generated by the `generateCenterConfig` Gradle task into `build/generated/centerConfig/center-config.json` and added to the assets source set. Modes:

| Mode | Trigger | Content |
| --- | --- | --- |
| plain | neither flag | no description asset at all |
| public CI / preview | `-PcenterConfigFake=true` | reserved domains plus pins derived from the checked-in test certificate |
| controlled packaging | `-PcenterConfigPackaging=true` | real values, read only from Environment variables |

A packaging run fails with a fixed `CENTER_CONFIG_*` code when a value is missing, placeholder, non-canonical, duplicated or when both roles share one origin with different pin sets. Real values never enter `-P` arguments, source files, tracked resources or the public CI workflow. The authoritative workflow is `.github/workflows/p2p-private-packaging.yml`: it is bound to a protected `p2p-private-packaging` Environment, accepts only a full 40-character SHA that is the reviewed head of the given PR, checks out that exact commit, disables the Gradle cache, captures sensitive tool output to a local file so raw diagnostics never stream into the public log, and uploads only the APK and its SHA-256.

## Verification

Verification lives in `.github/workflows/ci.yml` and `.github/workflows/p2p-preview.yml`.

- `python3 scripts/test_p2p_sandbox.py` asserts the native-engine invariants, the absence of the retired sinks, the pinned shared client, the signaling protocol separation and the cross-language vectors, then runs the server suites and the JVM sandbox.
- `python3 scripts/test_center_config_leak.py` fails if a generated description, a pin literal or a non-reserved host literal appears in tracked files.
- `python3 scripts/test_center_config_packaging.py` runs the real generator with a synthetic canary: a complete value set must produce a description only in the generated directory and never echo the canary into sources, workflows or logs; a missing, invalid or duplicated value must fail with a fixed code.
- The Android sandbox exercises production Keystore, double QR, TCP, revocation, independent Relay carrier authorization, route policy, duplicate SMS receipts, native engine readiness and the trusted-center gate, and 1,205-row history. The exact emulator-installed APK is exported with the results and checksum.
- Standard CI, CodeQL and OTA checks remain enabled and now also build with the fake description so the generator/loader path is exercised publicly.

No public signaling infrastructure is used or bundled any more. NAT/firewall restrictions can still defeat pure direct connectivity; no universal connection guarantee is claimed. Real SIM delivery, OEM background behaviour and store approval require external acceptance.
