## v0.12.0 — Android system compatibility lab and Shizuku authorization

- Introduce an **independent, adaptive System compatibility lab** under Android Settings to avoid mixing experimental system controls with the normal Relay, SMS and security setup.
- Integrate upstream Shizuku 13.1.5 API/Provider: detect the Binder, identify ADB/root service UID where permission is granted, request a separate per-app authorization, and handle permission results and service disconnection.
- Add Android 13+ user-confirmed self-managed CompanionDeviceManager associations for already-enrolled devices, including explicit safe disassociation and truthful OTP exemption caveats.
- Offer user-triggered OS/target SDK analysis, role/SMS permission and read-only OTP AppOp status, metadata-only SMS Provider probe, bounded encrypted SMS reconciliation, OEM battery settings and copyable read-only ADB/Rish diagnostics.
- Add a local **60-entry, metadata-only compatibility audit** independent of Developer Mode, with export inside the existing redacted diagnostics ZIP and a separate clear control.
- Add unit tests for SDK policy, association eligibility and audit token validation; maintain compatibility with v0.11.2 SMS ingestion.
- Do not attempt automatic SMS app disabling, OTP security flag overrides, AppOps privilege grants or unsafe notification-based OTP harvesting.

**Important:** Shizuku authorization, self-managed CDM association and successful Provider queries **do not prove** immediate access to protected Android 17 OTPs. Hardware testing on the target ROM remains required. See [compatibility lab](docs/COMPATIBILITY_LAB.md).

## v0.11.2 — Non-default Android SMS companion mode

- Receive `SMS_RECEIVED` broadcasts to wake encrypted SMS Provider synchronization without replacing vivo/OriginOS or other OEM Messages apps; preserve the `SMS_DELIVER` default-handler path.
- Reconcile a rolling six-hour window in bounded batches so messages withheld by Android OTP protections can appear when the OS permits access; deduplicate Provider IDs before encryption/upload.
- Support `SEND_SMS` remote and local sending without SMS-role ownership; the non-default handler must not write the SMS Provider.
- Preserve modem delivery acknowledgments when the SMS role or Provider write privileges change during an in-flight send.
- Show actual READ/RECEIVE/SEND permissions, companion/default runtime mode and Android 17 OTP restrictions in the native Android UI.
- Add JVM reconciliation-policy regression tests and update bilingual documentation.

**Platform limitation:** Android 17 may withhold protected OTP SMS from non-exempt companion applications for approximately three hours. OEM/installer SMS permission restrictions and unattended background behavior need physical-device validation; no system protection is bypassed.

## v0.11.1 — Rootless systemd user service compatibility

- Fix Ubuntu 24.04 rootless Docker `docker.sock` EACCES: remove `PrivateTmp=true` and `ProtectSystem=full` from the generated **user** service to prevent implicit user namespace/GID remapping.
- Keep strict non-root/Rootless Docker enforcement, fixed IPC operations, credential/digest verification and signature checking unchanged.
- Verify the actual launched updater shares the caller's user namespace and accepts a local status IPC request before the installer reports success.
- Add static regression tests for namespace-triggering systemd options and installer fail-closed behavior; document manual bootstrap recovery for v0.11.0 hosts.

**Important:** Hosts with the broken v0.11.0 service must pull the new installer and run `bash scripts/install-updater.sh` **as the dedicated rootless user**. The Web UI cannot repair a service that cannot reach Docker.

## v0.11.0 — Rootless signed OCI deployment

- Remove the root-run Docker source build/update path and disable legacy updater activation from the Web UI.
- Publish digest-pinned multi-platform OCI images to GHCR from the release workflow and sign them with GitHub OIDC/Sigstore.
- Require rootless Docker, pinned signer identity and Sigstore verification before any image is pulled or deployed.
- Isolate the update control socket with per-container-UID ACL, refuse unsafe requests and enforce fixed Compose/image operations.
- Preserve snapshot backups, readiness validation, protected deployment status and previous-image rollback.
- Provide a one-time non-root installer and explicit legacy root-service retirement/migration guidance.

**Migration:** The former host-root service must be disabled separately. Rootful Docker deployments use manual supervised upgrades until moved to Rootless Docker. GHCR package visibility and deployment permissions must be configured before the first automatic update.

## v0.9.1 — Relay and Android signed updates

- Add a restricted host-side Docker updater: GitHub Release source verification, online SQLite backup, tagged-image build, live readiness check and previous-image rollback.
- Keep Docker daemon control entirely outside the read-only non-root Relay; require explicit one-time host service installation.
- Add step-up protected update actions, durable status, latest-release checks and ignored-version preferences in Web settings.
- Add Android in-app OTA download, SHA-256 and release-certificate validation, Android PackageInstaller handoff and system confirmation notifications.
- Add persisted periodic Android update checks, opt-in automatic download/installation and per-release ignores.
- Publish a machine-readable stable update manifest and reject GitHub Release/tag replacement in the release pipeline.
- Expand automated metadata, archive and update security regression tests.

**Deployment:** existing Docker hosts require one-time `sudo bash scripts/install-updater.sh` after upgrading the service. Restore a database snapshot only under a deliberate recovery procedure; rollback of incompatible schema migrations is not automatic.

## v0.9.0 — Reliability and encrypted sharing hardening

- Persist pool-key rotation requirements after any member opt-out, administrator revocation, reset, or deletion. Quarantine new shared uploads until a Vault-authorized epoch rotation completes; preserve existing ciphertext and E2EE boundaries.
- Make terminal command ACKs immutable and repeated/stale ACKs idempotent; close the command long-poll lost-notification window.
- Retry interrupted shared pool uploads with persistent exponential backoff and continue draining 20-message batches across background jobs.
- Keep independently bounded Android local and remote message windows, and reject historical SIM attribution without a verified Channel ID plus Revision.
- Require revision-bound Web history labels and validated reply routing, with an explicit recoverable key-rotation pending action.
- Expand bilingual Compose messaging, dashboard, SIM and settings coverage while retaining one adaptive Activity.
- Add HTTP connection occupancy/rejection, SQLite busy error and pool-rotation metrics for capacity analysis.
- Bump the Relay/Android release version and SQLite schema marker to v11. Existing records remain encrypted and available across migration.

**Deployment:** relay upgrade, schema migration and key rotation require backup, production version verification and physical-device validation. Vault-backed key rotation cannot complete unattended when the Vault is unavailable.

## v0.8.0 — Encrypted SMS sharing and unified adaptive Android

- Repair Web inbox card compression and scrolling; preserve a separate conversation scroll area and load history incrementally as the list approaches the end.
- Show date/time, direction, source device, SIM label and masked four-digit provenance on individual SMS messages; clearly mark unverified historical SIMs.
- Add opt-in shared SMS via per-pool AES-GCM encryption, explicit device consent, administrator approval, independently Node-Key-wrapped Pool Keys, versioned rotation and paginated encrypted relay storage.
- Add encrypted offline Android cache and locally encrypted upload staging; restrict remote pool SMS to read-only unless separately authorized for sending.
- Consolidate pairing, optional contacts permission, background sync, reset, languages, OTA, developer logs and ZIP diagnostic export into one Compose settings page; delete the legacy advanced Activity and duplicate XML views.
- Use responsive Android dashboard/SIM grids alongside compact/expanded and hinge-aware SMS layouts; add server authorization, pagination and browser layout regression tests.

## v0.7.0 — Native Android UI and foldable adaptive experience

- Replace the Android Agent's default single long setup screen with an adaptive native Material 3 four-tab dashboard (Overview, Messages, SIM cards and Settings), retaining the legacy advanced tools as a secondary destination.
- Introduce scalable Android vector (SVG-path) icons, consistent color/typography, animated tab transitions and low-motion card feedback.
- Add local Android SMS conversations with search, OTP copying via sensitive clipboard tagging, recent-history paging, replies and SIM-aware sending; messages continue to be read from Android SMS Provider without a duplicate plaintext message database.
- Provide SIM state cards, a radio-detail page, clear device and permission alerts, simplified real-time mode and history synchronization controls.
- Support folding and unfolding, split windows, compact bottom navigation, expanded side rail, book/tabletop postures and in-memory retention of ephemeral device pairing state during configuration changes.
- Preserve Java Agent, Relay encryption, user data, device tokens and normal APK upgrade paths; test foldable navigation breakpoints.
- Known limit: carrier MMS media handling remains incomplete; exact OEM background behavior and fold postures require device validation.

## v0.6.0 — Secure pairing and conversational inbox

- Prioritize pending device commands and acknowledgements ahead of SMS history scans; separate transport health from Android SMS Provider permission failures.
- Attribute new historical SMS only to previously observed stable SIM identities; use a short unverified label for older ambiguous messages.
- Give desktop SMS threads and conversation panes independent fixed-height scrolling and compact the direct-reply composer; improve mobile/tablet responsiveness.
- Generate QR enrollment packages inside the PWA and scan locally in the Android app.
- Add Android-generated eight-digit pairing codes with five-minute expiry, mandatory administrator approval, P-256 ECDH transport encryption, and independent Node Keys; resume activation after network interruption.
- Include device-pairing API tests, browser layout regression and signed APK release.

## 0.5.3 — Device lifecycle and deployment identification

- Clear a selected device's encrypted SMS stored on Relay without touching SMS on Android or modem hardware.
- Bidirectional device-initiated/server-initiated pairing reset, with offline recovery, enforced access suspension, and force-delete tombstones.
- Device cleanup audit history, refreshed responsive management actions, and explicit second-factor confirmation.
- Android app version and installation date; Web management version with persistent deployment date.
- Database migration and purge/reset regression tests.

## v0.5.2 — Vault data hygiene, encrypted-key continuity and device reliability

- Purge SMS text, phone numbers, drafts, diagnostic data, Vault recovery fields and one-time enrollment secrets from the DOM and browser on manual/idle Vault lock; synchronize locks across tabs and reject late async content after locking.
- Avoid Android custom-scheme intent dispatch for enrollment packages: explicitly copy and paste into the SIM Hub Agent; require the Android user to confirm the HTTPS relay hostname before consuming credentials.
- Partition anonymous/auth, API and static rate budgets to reduce false throttling for multi-SIM installations sharing one public IP; maintain MFA and per-device rate limits.
- Archive only Vault-wrapped historical Node Keys at successful rotation and make older ciphertext readable by its original key ID, without storing plaintext on the Relay.
- Deduplicate previously acknowledged Android SMS uploads using an on-device receipt index; report scanned versus newly queued SMS counts.
- Expose authenticated, metadata-only recent command status, with a positive allowlist on device ACK results; show progress and fold low-frequency operations into device details.
- Add cross-tab Vault purge/browser regression, API integration tests, stronger CI security coverage for Android Java, and refreshed PWA cache assets.

## v0.5.1 — Responsive SMS and navigation hotfix

- Constrain all SMS conversation grid/flex tracks, long senders, previews, device/SIM metadata and message bubbles to their own columns; no clipping behind the conversation panel.
- Redesign desktop as a bounded two-column messaging inbox and tablet as stacked list + detail, with a one-at-a-time mobile conversation overlay.
- Fix the mobile bottom navigation as a persistent floating dock with side/bottom spacing, safe-area support and reserved scroll padding.
- Keep the message editor above the floating navigation and synchronize mobile conversation state across navigation changes, resize and orientation changes.
- Test long synthetic SMS content at 13 viewport sizes (320–1920 px), scrolling, message back navigation, and desktop/tablet panel geometry in Playwright.
- Refresh PWA cache to activate corrected styles; no SMS database, encryption format, or existing device enrollment changes.

## v0.5.0 — Session continuity, SIM identity and dependable diagnostics

- Align Vault and administrator idle expiry (default 8 hours), with a 24-hour absolute session limit and encrypted same-tab Vault recovery across refresh. The relay still never receives Vault plaintext; local XSS/device compromise remains a threat.
- Transmit discovered Android SIM numbers only in Node-Key-encrypted state envelopes, and support encrypted browser-local manual number overrides for carrier APIs that return no MSISDN.
- Display per-SIM phone identification, charging, Wi-Fi/cellular status and network fallback readiness in the Web Controller.
- Split newest-100 SMS rescan from the existing resumable older-100 backfill; keep batching, durable cursors and event-id deduplication.
- Open a remote diagnostic inspector for recent encrypted health results, linked by request ID. Modem nodes can produce diagnostic events.
- Add an Android-network-policy control: the OS chooses the configured default data SIM when Wi-Fi drops; unprivileged apps do not forcibly switch the default-data subscription.
- Ensure mutable SIM phone numbers and display labels are excluded from channel identity revision fingerprints; retain revision fail-closed checks for remote sends.
- Extend CI/Release regression tests; preserve the signed APK release gate and existing encrypted protocol compatibility.

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