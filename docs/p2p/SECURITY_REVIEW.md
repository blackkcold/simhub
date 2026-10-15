# P2P preview: CodeQL security adjudication (2026-10-15)

Scope: draft PR #84, `feature/p2p-isolated-preview`. This document tracks source-level mitigations, protocol necessities, and **remaining risk**. It is **not** a blanket finding dismissal, exemption, or proof of an all-green GitHub check.

| CodeQL rule | Source | Disposition | Rationale / controls |
| --- | --- | --- | --- |
| `java/android/insecure-local-authentication` | `PeerActivity.kt`, controller workspace | **Remediated; re-scan required** | `LocalAuthProof` generates an Android Keystore auth-per-use ECDSA key, authenticates with `BiometricPrompt.CryptoObject`, signs a fresh random challenge, and verifies it before granting local access. API 29 uses strong biometrics; API 30+ permits strong biometrics or device credential. Existing Vault wrapping keys remain unchanged. Verify success/failure/re-enrollment on real devices. |
| `java/android/websettings-javascript-enabled` | `PeerWebRtcTransport.java` | **Reviewed, intentional JS engine; residual risk** | Bundled `p2p.html` implements RTCPeerConnection and requires JavaScript. WebView blocks network loads, all other resources, external navigation, file access, content access, new windows and mixed content. Its WebMessage bridge is main-frame + exact-origin gated, and all peer RPC is independently authenticated/encrypted. Turning JS off breaks the feature; migrating to native WebRTC is a separate architectural project. Do not dismiss solely to make CI green; reviewer must validate controls. |
| `py/weak-sensitive-data-hashing` | `server/peer_connect.py` | **Identity-derived username removed; re-scan required** | The TURN username now contains a fresh 128-bit random suffix instead of an identity fingerprint, preventing identity disclosure and cross-registration linking at the TURN service. The coturn TURN REST temporary-credential **HMAC-SHA1** remains for protocol interoperability; this is not a password or user-data digest. Username is time-limited to 300s, configured only when user consent + TURN settings exist, secret stays server-side. Swapping to SHA-256 breaks the documented coturn configuration. HMAC-SHA1 must not be used for new non-protocol-sensitive purposes. |
| `java/android/missing-certificate-pinning` | `PeerCloudConnector.java` | **Reviewed, public endpoint; residual risk** | PeerJS Cloud is third-party operated and gives no managed backup-SPKI/rotation contract. Pins derived from a single observed certificate can break all users on rotation. OkHttp validates CA, hostname and TLS; redirects are disabled and code enforces exact `wss://0.peerjs.com:443/peerjs` origin. All SDP/ICE frames remain pair-key-authenticated and encrypted. Production options: operator-maintained signaling endpoint with documented dual pins, or explicit risk acceptance for public signaling. |

## Additional CI integrity correction

The earlier `android-sandbox` error annotation was from an **optional GitHub PR comment**, not from the Android instrumentation suite: the logs included `OK (12 tests)` followed by `HTTP 403 Resource not accessible by integration`. The workflow now writes the validated APK link into GitHub Job Summary and removes unused `issues: write` permission. The `simhub-p2p-tested-package` artifact is uploaded only when the test job succeeds.

## Release gates

1. Verify **the GitHub Advanced Security aggregate CodeQL check**, not just the successful CodeQL workflow. Record the exact active alerts and any decisions made by authorized security reviewers.
2. Verify standard CI, OTA, public dual-runner DIRECT, Android preview and emulator tests on the **same latest commit**.
3. Verify independent real-device P2P behavior: carrier NAT differences, sleep/network changes, local unlock on API 29 vs 30+, and Relay-first P2P-backup idempotency.
4. Deliver only the corresponding head SHA APK and checksum. Remain in draft and do **not** merge into `main` without user acceptance.

Sources: [coturn TURN REST](https://github.com/coturn/coturn/wiki/turnserver), [Android auth-per-use keys](https://developer.android.com/identity/sign-in/biometric-auth), [CodeQL WebView security guidance](https://codeql.github.com/codeql-query-help/java/java-android-websettings-javascript-enabled/).
