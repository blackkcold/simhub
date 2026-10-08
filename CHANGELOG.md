## v0.4.0 — Secure guided deployment and unified SMS conversations

- Fix Issue #23: trust only the exact Docker default-route gateway for loopback-published ports, preserve forwarded client IP isolation and restore host health probes.
- Deny public health endpoints at managed/default Caddy proxy; add configuration regression checks.
- Add a Linux setup wizard with managed HTTPS Caddy or existing-proxy mode, DNS diagnostics, strong bootstrap secrets, strict admin/node hostname separation, and non-destructive upgrades.
- Add configurable administrator username and WebAuthn/FIDO2 Passkeys for sign-in and sensitive-action confirmation; existing admin token + TOTP remains a recovery option.
- Keep browser Vault keys independent of Passkey authentication and Relay session cookies; no unencrypted SMS content is transmitted to Relay.
- Combine the SMS viewer and sender into threaded inboxes with direct reply, SIM-aware routing, new-message action and mobile-responsive composer.
- Standardize modal and form spacing, invalidate old PWA assets, and add UI browser screenshot smoke tests.
- Preserve existing Android/Modem encrypted command protocol and staged message history loading.

## v0.3.2 — SMS history performance and device enrollment reliability

- New Android enroll safeguard: prevent duplicate submissions and move telephony/SIM status collection off the main thread.
- Replace browser-native enrollment step-up prompt with an accessible in-app modal; expose the Add Device action within the Devices view.
- Bootstrap from the newest 100 SMS and keep independent durable cursors for new-message polling and user-requested older-history backfill.
- Add explicit 'Load 100 older SMS' in Android and cap remote backfill commands to 100.
- Upload 20 E2EE events per request using an atomic, idempotent Relay batch API with per-event confirmations. Preserve older single-event API.
- Classify HTTP failures and honor rate-limit Retry-After using delayed JobScheduler retries.
- Avoid per-history-event wakeups; prioritize real-time SMS in the local queue.
- Introduce time-based paged message queries and 30-item initial Web rendering; reduce redundant cryptographic key invalidations and realtime refresh overlaps.
- Add batch atomicity, replay, pagination and malformed-input regression tests.
- Maintain existing Device Token, Node Key, encryption envelopes and administrator MFA protections.

## v0.3.1 — Management UI security update

- Introduce independent management and node API origins.
- Add browser CSRF protection, origin verification and server-enforced idle session expiry.
- Introduce second-factor confirmation before sensitive changes.
- Add resource budgets for network connections and remote SMS transmissions.
- Fix HTML escaping for live device telemetry in the browser.
- Add deployment guidance, security integration tests and a CodeQL CI workflow.
- Preserve existing encrypted SMS and node transport protocols.

## v0.3.0 — Localized UI and developer diagnostics

- Redesign the Android Agent UI into a card-based dashboard with responsive single-column phone and two-column foldable/tablet layouts.
- Add native light/dark themes and Simplified Chinese / English localization with per-app language selection.
- Add opt-in Developer Mode with rotating app-private diagnostic logs, recent-log viewing, clearing and ZIP export.
- Redact SMS bodies, OTP values, authentication tokens, encryption/recovery secrets and phone-number middle digits before diagnostic persistence.
- Add diagnostic instrumentation for enrollment, Relay lifecycle, API sync, SMS receive/send/history, JobScheduler and remote command processing.
- Add regression tests for diagnostic redaction.
- Localize the Web/PWA Controller in Simplified Chinese and English with automatic browser-language selection and manual switching.
- Add contextual `ⓘ` guidance for Admin Token, TOTP, Vault passphrase, node type, recovery key, notifications and security controls.
- Localize dynamic device health, message state, confirmations, notifications and common error/toast messages.
- Cache the localization module in the PWA shell for offline use.
- Preserve the existing E2EE, Node Key, enrollment, Channel routing and Relay protocol behavior.

## v0.2.2 — SMS reliability and operational health

- Make enrollment token consumption atomic under SQLite write locking; include concurrency regression test.
- Prioritize live inbound SMS over historical backfill, cap automatic history imports, and load the newest events in PWA first with older-event pagination.
- Reject remote Android SMS when default SMS role is unavailable; report role, permission, SIM and last-success/error metrics.
- Require actual SIM ICCID/IMSI in direct AT and ModemManager adapters; reject unknown identity and recheck immediately before remote modem sending.
- Prevent silent multipart part overwrite and use content-distinct multipart event IDs.
- Treat interrupted/uncertain Android and Linux modem SMS submissions as submitted/unknown, not safe for automatic resend.
- Recognize OTP locally on Linux/DJI modem nodes without leaking codes outside E2EE.
- Synchronize critical Android Keystore-wrapped credential writes and SIM identity revisions.
- Preserve MMS WAP PUSH PDU encrypted in private device storage and alert users; full carrier MMSC media download remains unsupported.
- Add native generic metadata-only Bark and ntfy push adapters, plus a multi-modem systemd template for independently enrolled nodes.
- Retain v0.2.1 release-signing identity and mandatory pinned-certificate verification.

## v0.2.1 — Production hardening

- Require release-signed Android artifacts with pinned certificate verification; remove debug-release fallback workflows.
- Pin GitHub Actions dependencies to immutable commit SHAs and separate signing from release publication permissions.
- Strengthen Android SIM replacement detection with card, port, subscription, carrier and ICCID/number signals; stale commands continue to fail closed by Channel revision.
- Replace new-node plaintext Node-Key enrollment with a one-time Bootstrap Secret and encrypted Node-Key envelope; verify a SHA-256 bootstrap proof before consuming the one-time token.
- Enforce PWA Vault timeout using absolute inactivity time across browser suspension/backgrounding.
- Correct Android SMS history direction handling for FAILED/QUEUED/OUTBOX/SENT and skip drafts.
- Add encrypted durable multipart receive staging and reassembly for DJI/Quectel modem SMS.
- Make relay TOTP and 30-day event retention secure defaults.
- Replace per-event webhook/push threads with a bounded outbound worker pool.
- Add shared bootstrap crypto vectors across WebCrypto, Android/JVM and Python Modem tests.
- Reject USSD/service dialing symbols in Modem SMS destinations so they cannot be silently normalized into a different number.
- Schema version 6 adds one-time enrollment bootstrap ciphertext plus a non-secret SHA-256 bootstrap proof digest.

# Changelog

## v0.1.5 — Reliability & Security

### Reliability
- Scope event identity by Android device to prevent cross-device SMS Provider ID collisions.
- Atomically claim commands before side effects and serialize Android sync cycles.
- Persist command acknowledgements locally and track SMS states as submitted, sent, delivered or failed.
- Recover stale pre-side-effect command claims after process interruption.
- Use a lossless `(SMS date, provider ID)` history watermark that advances only after durable local queueing.
- Restore user-enabled relay mode after reboot and debounce JobScheduler wakeups.
- Merge outbound history/status events in the PWA by provider identity.

### Security
- Add per-device HKDF-SHA256 keys derived from the recovery/master Vault Key.
- Add v2 AES-256-GCM envelopes with `kid` and metadata-bound AAD.
- Preserve v1 event/command compatibility for rolling upgrades.
- Replace persistent browser Admin Token storage with short-lived Secure/HttpOnly/SameSite sessions.
- Add recovery-key import to the PWA.
- Use strict relay-readable metadata/state allowlists.
- Add basic authentication failure rate limiting and a strict Content-Security-Policy.

### Operations
- Add SSE realtime wakeups with 60-second polling fallback.
- Add metadata-only push/tickle adapter hook for FCM/OEM bridges.
- Prevent history sync from generating new-message webhook floods; add OTP/device/freshness filters.
- Add automatic SQLite schema migration, subscription projection, metrics, retention/purge controls and online backup helper.
- Support stable Android release signing from GitHub Secrets without committing signing keys.

### Scope
- Phone-call functionality remains intentionally excluded.
- Full carrier-specific MMS send/download remains out of scope.