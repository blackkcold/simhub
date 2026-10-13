## v0.17.0 — Encrypted history recovery, source-safe threads and fluid controller UX

- Keep encrypted SMS history on new Relay installations by default (configurable via `SIMHUB_EVENT_RETENTION_DAYS`; existing deployments preserve their configured retention). A recovery command can replay locally retained Android Provider messages after automatic expiry without bypassing explicit server-side purge tombstones.
- Add an administrator-step-up-protected `sms.restore_history` action with explicit Web and native Android confirmations. Initial recovery imports the latest 100 Provider SMS, then repeated older-history commands can restore earlier pages; server event IDs remain deduplicated.
- Add `scope=sms` to Relay keyset and incremental pagination. Web/PWA and Android history browsers request bounded SMS-only pages; preserve auxiliary SIM profile and operational event bootstrap.
- Align Web thread identity with Android: physical device, stable Channel ID, revision and canonical correspondent. Prevent distinct SIMs sharing a phone number from being silently merged.
- Cache per-SIM profile metadata across Web render passes and wipe that cache on Vault lock or data reset. Retain authenticated memory-only SMS handling and encrypted key hierarchy.
- Improve native management inbox readability with scroll-direction-aware collapsing toolbar and independent, stable conversation list position. Enrich task statuses without presenting queued work as successful.
- Poll active native command progress more often while reverting to lower-frequency idle checks. Improve accessible Web toast semantics, focus feedback, and restrained panel/list animations, respecting reduced-motion preferences.
- Keep the existing E2EE protocol, AAD bindings, default SMS versus companion send restrictions, immutable APK signing, Rootless Docker updater and verified OTA release chain unchanged.
- CI/CodeQL verifies software behavior; physical SIM/Doze/OTP restrictions, OEM installation prompts, long-running restore and hardware-auth-bound Keystore migration still require the documented on-device gates.

## v0.16.8 — Controller history, compact native inbox and secure device management

- Fix the first native management inbox load so a bounded 50-event response initializes both historical time/sequence pagination and the incremental high-water mark. All retained encrypted events remain reachable through repeated pagination; 1200 messages is a client memory window, not a relay history cap.
- Streamline native management SMS UI with on-demand search/source filters, a collapsible Compose app bar on scroll and human-readable SIM source labels. Preserve hidden Channel ID/Revision in identity and E2EE routing.
- Align desktop/mobile Web navigation labels, anchoring the unread-count badge to the SMS icon without shifting the sidebar text.
- Expand native node details with rename, mobile network fallback policy, step-up-protected relay SMS purge, authorization revocation and forced node deletion. Native settings also read shared pool membership/rotation status and recent metadata-only audit entries.
- Add a limited authenticated POST bridge for native rename/revoke because Android HttpURLConnection cannot issue PATCH; preserve server-side admin/step-up authorization and tests.
- Preserve existing signed-release and Global Vault/Node-Key contracts. Initial node enrollment, browser WebAuthn and cryptographic shared-pool grant/rotation still use trusted HTTPS management until equivalent native ceremonies are validated.
- Relay event retention remains configurable (default 30 days). Client pagination cannot recover data previously purged from Relay or absent on the original phone. CI is not a substitute for OEM/foldable/device testing.

## v0.16.7 — Compact native SIM Node settings and visual UX

- Replace the excessively long SIM Node settings stack with a live operational summary, immediate sync and five categorized native settings destinations: connection/permissions, runtime/synchronization, sharing/privacy, application updates and advanced tools.
- Provide responsive single-column phone and independently scrolling foldable/tablet master-detail views; retain a stable settings category during tab switches and use Android Back to return to the settings hub.
- Condense the three energy profiles into compact icon-led selectors. Keep manual latest/older 100-message scans visible; show actual queued events, pending ACKs and last sync, while hiding verbose task metrics until explicitly expanded.
- Add a cumulative, zero-to-one command-check success indicator only when actual nonzero counters exist; do not invent historical trends. Display current Relay/SMS Provider errors even when diagnostics are collapsed.
- Move automatic APK update controls out of developer diagnostics. Preserve update checking without enrollment, Android 16+ remote OTA limits, local permissions and system installation confirmation.
- Supply six matching editable SVG and Android VectorDrawable icons, native settings UI preview, bilingual documentation, and source/asset parity checks.
- Preserve all existing pairing/reset recovery, relay command delivery, encrypted SMS Vault sharing, companion/full SMS mode rules, developer overrides and underlying background sync logic. OEM, foldable and accessibility validation on physical devices remains necessary.

## v0.16.6 — Android unpair lifecycle and Relay status recovery

- Clear enrollment-scoped Relay errors, last-success timestamps, upload statistics and command diagnostics when a SIM node is unpaired or newly enrolled; retain system SMS, local SIM status and user device settings.
- Recognize a deleted device after an HTTP 401 only when a token-bound lifecycle receipt confirms deletion. Never treat an arbitrary unauthorized response as permission to destroy local credentials.
- Run manual device-unpair confirmation promptly while preserving persisted background retry; no longer report a remote refresh or SMS check as successful when it actually found a reset.
- Display distinct Android SIM-node connection states for active pairing, reset pending, recovery required and unpaired. Suppress stale transport diagnostics and use contextual actions outside active enrollment.
- Add JVM regression tests for lifecycle presentation and enrollment telemetry cleanup. Keep existing Relay 409/410 reset protocol, token tombstones, encrypted SMS storage and signed release pipeline unchanged.
- Requires real Android handset/OEM acceptance testing for process death, network loss during reset and manufacturer-specific JobScheduler delays.

## v0.16.5 — Reliable update discovery and security scan coverage

- Fix manual server update checks so they refresh the published stable GitHub Release rather than reusing the normal 15-minute background cache; coalesce rapid repeated checks and cap release metadata downloads.
- Separate latest-version discovery from Rootless Docker updater readiness, report agent failures accurately and distinguish update availability, install eligibility, ignored releases and GitHub retrieval errors in the management UI.
- Introduce strict streaming limits for both Android SIM Node and native management controller HTTP responses; close HTTP connections on read failures and cover limit/Unicode behavior in Android unit tests.
- Upgrade CodeQL to v4 and compile Kotlin/Java using the actual Android 37, Gradle 9.6 and JDK 17 toolchain so Kotlin files are included in source analysis.
- Add update-cache regression tests and refresh public-exposure documentation to reflect the actual 24-hour absolute / 8-hour idle admin session defaults.
- Preserve existing E2EE/Vault, SMS, device pairing, Shizuku authorization, signed APK verification, immutable releases and verified Rootless OCI update architecture.

## v0.16.4 — Notification center and browser alert reliability

- Fix notifications hidden behind native modal backdrops by moving the toast host into the active dialog top layer; retain accessible, mobile-aware feedback.
- Add categorized browser notification preferences and permission/help states, with a Service Worker notification path and notification-click routing. Closed-browser Web Push is not included.
- Add local, deduplicated notification history (maximum 500 metadata-only events retained for 30 days), per-event SMS unread badges, mark-read and clear-history controls.
- Detect sustained device-offline transitions and confirmed remote task/OTA outcomes; avoid announcing queued commands as successful, and never store SMS body, OTP or Vault key in notification metadata.
- Preserve encrypted Relay/SMS behavior, existing Bark/ntfy/Webhook integrations and Android node/controller capabilities.

## v0.16.3 — Categorized device health dashboards

- Reorganize Web/PWA device details into responsive cards for device health, SIM/SMS permissions, real sending capability, sync/ACK queues, network/power, chronological event timestamps and diagnostics. Add inline SVG battery and capability indicators.
- Upgrade native Android controller device details with Material 3 metrics, native battery gauge, grouped SMS permissions, sync counters, reported activity and diagnostics, preserving all existing sync, remote command, OTA and SIM-channel actions.
- Keep missing telemetry explicitly unknown and avoid misleading upload percentages or fabricated history curves. Report offline status as last known data and distinguish companion SMS role from effective send authorization.
- Preserve existing E2EE, SMS delivery, server command protocol, enrollment and background power behaviors. Add Web sanitization/no-data tests and native snapshot unit tests.
- Android/OEM visual acceptance and accessibility review on physical phones/foldables remain recommended after CI builds.

## v0.16.2 — Immediate node actions, reset handshakes and Android compatibility lab

- Manual node refresh performs an authenticated Relay request and state upload instead of refreshing only local Compose state. Manual command/SMS checks immediately scan, process acknowledgements and flush encrypted events independent of background maintenance cadence.
- Foreground user-enabled Relay service checks encrypted commands via server-held HTTPS long polling across energy modes. Without a running service or push wake signal, Android Doze/OEM restrictions still prevent guaranteed instant remote delivery.
- Relay wakes in-flight command polls when an administrator requests reset, and Android recognizes the reset-specific HTTP 409 signal, immediately attempts the authenticated reset acknowledgement, and bypasses normal sync backoff. Offline devices cannot confirm removal until next contact; no premature acknowledgement is claimed.
- Add a local-only Shizuku UserService/AIDL Shell runner and native Compose diagnostics console (presets, developer commands, stdout/stderr, exit codes and bounded execution). No HTTP or Relay endpoint can call privileged Shell; Shizuku does not grant SMS/OTP exemptions.
- Declare and probe Android Companion Device Setup feature, keep the self-managed association behind system confirmation and surface OEM SecurityException details for diagnostics.
- Add lifecycle long-poll reset unit tests and Android CI coverage. Physical Android 16/17, Shizuku and OEM association behavior require device acceptance tests.

## v0.16.1 — OTA completion reconciliation and reliable device version sync

- Reconcile remote Android APK updates with PackageManager-installed versionCode at startup, replacement broadcasts, background synchronization and installer callbacks; never equate submission with installation.
- Persist OTA revisions across retries; bind installer callbacks to the active PackageInstaller session and show an explicit verification-pending state for stale install requests.
- Refresh full device inventory on APK replacement and coalesce successful encrypted SMS uploads into version/online metadata heartbeats no more often than two minutes.
- Atomically synchronize Relay device-list appVersion with full inventory, and let authenticated heartbeat update versionCode without falsely refreshing SIM inventory timestamps.
- Present the current installed version and actual OTA verification/ACK distinction in Web/PWA and native Android controller. Preserve Android 16+ feature gating, system approval, SMS-role policy, E2EE, Rootless Docker and existing updater behavior.
- Add Relay version reconciliation tests and Android policy regression tests. Physical OEM Android 16/17 confirmation and low-power behavior remain hardware acceptance tests.

## v0.16.0 — Native controller UX overhaul and SMS-mode-aware actions

- Introduce dual Material 3 semantic themes: teal for the local SIM node and indigo for the native management center, with dedicated dashboard/device navigation vector icons and matching editable SVG sources.
- Replace the text-heavy controller overview with metric cards, quick actions and compact durable-ACK task items.
- Rebuild device lists as icon-led cards and use a full-screen native device detail with SIM channels, permission summary, sync, diagnostics and capability-gated remote OTA commands.
- Reorganize controller settings into management address, security and system/update cards.
- Implement a conservative SMS send capability policy across Android and Web: companion/non-default SMS mode is read-only; full default-SMS role additionally requires SEND_SMS; temporary one-hour developer send overrides must be explicitly reported, visibly labeled, and remain subject to node-side enforcement. Modem send ability is independent of Android role.
- Gate new-message and reply UI by current sending capabilities; in-conversation reply pre-fills only the recipient and requires the user to verify the sending device and SIM.
- Continue using the existing Global Vault E2EE, management signing and server ACK state machine; advanced destructive management and identity/Vault flows remain on the trusted HTTPS web console.
- Add coverage for companion/default/developer/modem SMS capability decisions. Hardware, OEM, foldable and Passkey/biometric acceptance still requires physical-device validation.

## v0.15.8 — Reliable controller sync and compatibility

- Negotiate pairing protocols: pre-v0.15.7 Android clients use temporary IP-bound v1 polling; v2 requires a separate high-entropy polling token. Legacy v1 expires 2027-02-11 UTC.
- Fetch E2EE SMS events in batches of at most 50, split encoded oversized JSON, and advance sequence cursors only after each native Inbox ACK. Malformed pages are rejected rather than silently discarded.
- Separate delta refresh, older pages and latest navigation; keep a movable 1,200-message in-memory window for deep history and source-isolate conversations by Node/Channel/Revision.
- Continuously read durable command ACK history from Relay (including Eco wake delays); queued/dispatched cannot be mistaken for successful execution.
- Prevent Android management screenshots and Recents previews with FLAG_SECURE. Automatic API polling no longer renews administrator idle activity.
- Replace SMS compose AlertDialog with full-screen native UI retaining draft/SIM/idempotency keys across dismissals.
- Add legacy pairing regression, 2,050-event HTTP pagination, native overflow/history/source tests.
- Remaining external validation: physical SIM/OEM power behavior, biometric CryptoObject/Keystore rewrap security migration and high-connection relay performance. CI cannot certify these.

## v0.15.7 — Native controller audit remediation

- Fix controller pairing across Wi-Fi/mobile-data or VPN egress changes with a separate high-entropy polling credential. The Relay stores only a hash; Android seals the pending pairing token using Keystore AES-GCM. Pending pre-upgrade pairing requests retain temporary IP-bound compatibility.
- Replace Android native SMS page replacement with deduplicated, bounded in-memory history merging; use incremental server sequence fetching for new encrypted messages and stable Node/Channel/Revision source filters.
- Match native send step-up parameters to the Relay's TOTP vs administrator-token policy, mask sensitive authentication inputs, and mark clipboard OTP content sensitive on supported Android versions.
- Track native remote command states from the server's durable ACK records. Distinguish queued, dispatched, submitted, sent, delivered, succeeded, failed, rejected and expired states.
- Preserve an uncertain SMS send draft and its idempotency key on retry, preventing duplicate remote commands. Require restoring the original recipient/SIM/body before retrying with the same ID.
- Provide an explicit cloud-revoke-and-local-delete controller workflow, with an offline local-only fallback. Clear the native decrypted inbox if controller authorization is rejected.
- Add optional Android system biometric/device-credential **application gate** on returning from background; clear displayed messages on backgrounding. This **does not** yet bind Vault Keystore unwrapping to per-use user authentication.
- Improve native settings/compose dialog scrollability, copy source filters and localized core controller UI actions. Add pairing, pagination, source-contract and migration tests.
- Existing Global Vault, per-node E2EE keys, signed release process, and existing deployment volumes remain unchanged. Real OEM, physical SIM and biometric-provider tests still require hardware validation.

## v0.15.6 — Unified Android controller UI and same-phone authorization

- Consolidate the Android SIM node and controller into one Compose App Shell: consistent brand toolbar, role switch, four-tab navigation and adaptive large-screen navigation rail; no second controller-specific toolbar or embedded WebView.
- Add a native management overview dashboard; SMS, devices and settings reuse shared Material 3 cards and status components with independent navigation state per mode.
- Allow **initial controller trust authorization entirely on the same Android phone**: jump to its HTTPS management site, use the browser's existing WebAuthn Passkey (including supported device biometrics) or administrator password/TOTP, unlock the original Global Vault and explicitly approve the Android identity and 6-digit pairing code.
- Return to Android via a credential-free `simhub://controller?resume=1` link and resume pending enrollment; Keystore AES-GCM protects the time-limited ephemeral ECDH pairing state against ordinary process eviction.
- Preserve E2EE ECDH/HKDF/AES-GCM one-time Vault grants, device-signed sessions, singleton Global Vault, Node Keys, historical source identity, existing SMS and PWA/Relay behavior. Add source-contract CI checks for shared navigation and same-phone onboarding.
- The biometric/passkey ceremony uses the **same phone's secure browser**, not native Credential Manager or biometric-gated Android Vault unsealing. Actual OEM/passkey compatibility and physical process-death recovery still require device-level validation.

## v0.15.5 — One Global Vault across Web, PWA and native controllers

- Introduce a singleton, zero-knowledge Master Vault registry: the Relay stores only a password-wrapped AES-GCM envelope, not plaintext keys.
- New Web/PWA clients use the existing Vault password to unlock the same cloud-held encrypted envelope without creating another Vault or importing a recovery key.
- Migrate a browser-local pre-v0.15.5 Vault into the singleton once; reject unrelated recovery keys and competing initializations.
- Allow administrator step-up rewrap of Vault password, preserving all independent SIM Node Keys and encrypted SMS; bind native Android controller grants to the unique Vault ID and fingerprint.
- Maintain the separate, opt-in device-to-device Shared SMS Pool and current v1/v2 history decryption compatibility; users may re-enroll test devices and re-sync original SMS.
- Add API, unit and isolated-browser regression tests. Physical Android validation and deployment testing are still required.

## v0.15.1 — Native Android controller and independent encrypted grants

- Replace the management-center WebView workspace with Jetpack Compose inbox, devices and settings, retaining a single shared top toolbar and native bottom navigation.
- Add explicit first-use Android management-terminal approval to an already authenticated, unlocked PWA; the setup QR alone conveys no credentials.
- Generate Keystore-backed P-256 controller identity, transfer Master Vault only as an ECDH/HKDF/AES-256-GCM sealed envelope, and sign fresh challenges for subsequent passwordless administrator sessions. Vault plaintext remains off the Relay.
- Add server-side controller registry, grant expiry, one-time signature challenge, revocation and session invalidation; database schema migrates in place to 13.
- Keep existing node pairing keys, encrypted messages, SMS sources, Relay API, PWA and Docker updater intact.
- Fix native SMS decryption counters; preserve current/pending/history Node Key ciphertext compatibility and mark unverified historical SIM provenance.
- Include pairing/replay/revocation regression tests and GitHub Actions Android/Relay builds. Physical Android/OEM, real SMS sends, passkey-provider behavior and deployment-specific proxy conditions still require live verification.

## v0.15.0 — Native controller inbox, Android WebAuthn and unified navigation

- Management center SMS threads and messages now render as **native Jetpack Compose**, with search, OTP filtering/copy, device and SIM labels, conversation history, and paged refresh. The existing browser-side E2EE implementation decrypts SMS in the verified WebView before forwarding bounded, key-free read-only snapshots to Android process memory. SMS plaintext is not persisted by the native UI.
- A single standard **Android Material 3 bottom navigation** (SMS / Devices / Settings) replaces the duplicate floating Web/PWA dock within the management workspace; native node mode retains its bottom navigation. Embedded web pages hide their own chrome and preserve the Vault gate.
- Android WebView enables **system Credential Manager / WebAuthn** only on supported WebView versions; WebAuthn still uses the server's existing signed-challenge verifier and requires valid website-to-app Digital Asset Links. The administrator's HTTPS management origin serves `/.well-known/assetlinks.json` using the verified official APK signing certificate. Forked/self-signed packages require `SIMHUB_ANDROID_CERT_SHA256` override.
- The native inbox channel accepts only bounded messages from the active, HTTPS, same-origin top-level page. Vault lock and profile removal clear the native decrypted list; the Master Vault Key and device secrets are never exposed to Kotlin.
- Sending SMS retains the existing Web secure composer, channel validation, explicit confirmation and step-up authentication inside the **same Android shell**, accessible from the native inbox's Send action.
- Existing SIM Node synchronization, device enrollment, Relay ciphertext, profiles, sessions, updater and database schema remain compatible. No data migration is required.
- Verification includes server Digital Asset Links host separation, JavaScript SMS helpers, PWA session/lock regression and Android compile/unit tests. Actual passkey provider behavior varies by Android WebView/GMSCore/OEM and must additionally be tested on a linked HTTPS deployment and a physical device.

## v0.14.1 — Unified Android surfaces and QR management onboarding

- Replace the independent Android Controller Activity chrome with one shared Material 3 Compose shell for both SIM node and management center; make mode switching a prominent in-app selection, with node-only navigation and manager-only PWA navigation.
- Keep the controller WebView alive while switching to the local SIM node. Avoid destroying browser sessionStorage and forcing a needless Vault re-unlock on every switch. Node foreground service, SMS permissions, queues, encryption and OTA remain separate from the controller session.
- Reuse stored v0.14 controller profiles without migration or re-pairing. Support multiple HTTPS management addresses, scanning a QR, pasting a link, opening in an external browser and removing shortcuts without touching server data.
- Add **Devices → 连接管理手机** to the Web management console. The QR contains only `simhub://controller?url=<encoded HTTPS management origin>`; no SMS enrollment tokens, cookies, admin passwords, OTP or Vault keys.
- Require HTTPS origin validation, show the host and explicit confirmation before import, and fail closed on WebView certificate errors or cross-origin navigation/subrequests. Users still perform independent admin login and Vault unlock.
- Add controller-link parser tests plus browser UI regression coverage.
- Existing Docker data, admin accounts, session flow, Node Keys and SMS ciphertext are not modified. No new deployment steps are necessary.

## v0.14.0 — Android dual-role controller and staged administration

- Android APK offers a native SIM node, a trusted-origin management-console WebView, and dual-role onboarding. Users may select multiple independent HTTPS management spaces without pairing their controller-only handset as a SIM node.
- Preserve native node runtime, independent Node Key storage, original background sync, OTA update, SMS roles and existing relay protocol. Management-site JavaScript cannot access native SMS or Node secrets through a bridge.
- Replace the four-field login gate with administrator password, conditional TOTP challenge dialog and a separate client-side Vault unlock step. Passwords use Argon2id; the administrator can provision/rotate an independent password from the settings page after step-up verification.
- Keep the emergency Admin Token login path for migration. A short-lived, single-use authentication challenge does not grant a session until the second factor passes. Password changes revoke active sessions.
- Support opt-in trusted-controller Vault snapshots protected by an origin-bound non-extractable WebCrypto key, with bounded expiry and explicit lock/logout removal. This convenience mechanism is not hardware-backed and does not protect against malicious authenticated same-origin scripts.
- Add migration and end-to-end authentication replay tests plus Android/Web UI regression coverage; keep recovery keys necessary for new controller devices.
- **Deployment:** existing Docker/SQLite data is preserved; database schema advances to 12. Existing installations may log in with the Admin Token and provision a new password from account settings. Controller-only devices need their own management HTTPS URL.
- **Limitations:** WebView Passkey compatibility varies by device; the system browser remains a fallback. Physical Android dual-role, WebView and biometric hardware validation remain necessary. Multiple Relay profiles are independent, not automatic mutual Vault authorization.

## v0.13.3 — Android 16+ Remote OTA

- Android 16 (API 36) and later: explicit device-authorized remote APK self-update via encrypted relay commands and administrator step-up; earlier platforms have a developer-only test override.
- Preserve the existing verified GitHub Release signature/hash/package installer; Android may still require user confirmation.
- Durable update state and post-install service recovery via `MY_PACKAGE_REPLACED`, with a best-effort JobScheduler fallback.
- Device UI gates and Web capability-aware controls avoid claims of silent install or forced foreground launch.
- Wi-Fi-only remote updates by default, low-battery guard and pre-commit cancellation; status reports are independent from regular SMS polling.
- See [Remote OTA](docs/REMOTE_OTA.md).

## v0.13.2 — Read-only companion SMS, consolidated threads and updater restart resilience

- Make non-default Android SMS companion mode receive/read-only by default. Hide SMS compose and reply actions in the Android UI and Web/PWA. Allow explicit developer-only testing for up to one hour; the real Android SMS sender and encrypted remote command executor enforce the same policy.
- Report SMS receive readiness independently from send eligibility and avoid requesting SEND_SMS in standard companion mode. Existing full default-SMS and modem send paths remain available.
- Consolidate conversations by canonical correspondent rather than creating one thread per SIM/device/revision. Preserve each SMS's original device, channel ID and SIM revision, and make list previews use the latest actual message.
- Display the source SIM beside conversation timestamps and enable local per-SIM filtering on Android and Web, without changing server plaintext indexes or deleting SMS history.
- Improve the existing single-container signed rootless updater with consecutive readiness probes and switchover timing. Add safe GET/HEAD retry guidance to the existing Caddy sample and bounded idempotent-read retries in the PWA; never blindly replay POST commands.
- Add capability, updater readiness, and read-only UI regression checks.

**Deployment compatibility:** Same Compose service, rootless updater, database schema, keys and data volume. No new installation procedure is introduced. Existing custom Caddyfile copies are not overwritten by upgrades; operators can optionally copy the safe-read retry directives into their current Caddyfile and reload Caddy without redeploying containers.

**Validation:** CI validates software logic. Physical Android SMS behavior, OEM-specific permissions and live proxy restart timing need on-device/deployed-environment testing.

## v0.13.1 — Reliable SMS ingestion, command delivery and adaptive energy UI

- Fix a same-second SMS Provider notification race using a monotonic change generation and acknowledgement of only the generation observed before scanning.
- Scan the latest 100 Provider rows after a dirty event to recover messages the system persisted with a date earlier than the incremental history cursor.
- Read and stage SMS into the encrypted local queue before Relay networking, including during upload retry backoff; immediate/Provider follow-up jobs have no network prerequisite. Resume upload after validated connectivity returns.
- Retrieve remote commands independently of 15-minute device telemetry; Balanced foreground checks use about 45 seconds when interactive and 2 minutes otherwise. Devices without a working foreground listener schedule best-effort durable command fallback.
- Distinguish the foreground-relay enabled preference from actual listener liveness; update mode scheduling without stopping the Android foreground service.
- Add manual command check + SMS sync, command retrieval/error checkpoints, Provider change/scan diagnostics and server-side expiration of stale queued commands.
- Replace truncated horizontal power chips with responsive, fully clickable, vertically stacked Material 3 radio cards with native vector symbols.
- Preserve encryption, device/command idempotency, Android 17 OTP restrictions, app OTA independence and secure v0.12.2 unpair recovery.

**Verification:** Software CI/CodeQL can verify source behavior, but real-device Doze, OEM power policy and SMS timing require field testing. No automatic push service is configured in the APK.

## v0.13.0 — Android energy-aware event-driven relay

- Introduce Eco / Balanced / Realtime profiles, with Battery Saver auto-throttling unless explicit Realtime is selected.
- Replace the 20-second full sync and overlapping permanent long-poll with a dedicated command transport and bounded 15-minute inventory maintenance.
- Gate six-hour SMS Provider reconciliation to 30-minute idle cadence, resume bounded pages and retain delayed companion-mode SMS Provider follow-up after broadcasts.
- Short-circuit already-seen Provider event IDs before contact lookups and AES-GCM encryption; prune durable upload receipts at most daily.
- Coalesce JobScheduler triggers without replacing in-flight jobs, preserve missed event retries and shared-pool retry due times.
- Surface metadata-only work counters, adapt web command expiration to the last reported energy mode, and warn before delayed SMS sends.
- Preserve v0.12.2 independent signed APK OTA and secure unpair recovery; calculate Android presence windows from reported power policy without changing modem defaults.

**Limitations:** Software CI cannot verify hardware battery savings, OEM Doze exemptions, protected OTP visibility, or time-critical remote sends under restrictive ROMs. Optional metadata-only push adapter still requires an external push provider; no configured FCM credentials are bundled.

## v0.12.2 — Reliable device unpair recovery and independent Android OTA

- Separate ACTIVE, RESET_PENDING, RECOVERY and UNPAIRED Android states so remote unpair no longer renders a false "unpaired" screen while local credentials are retained.
- Make reset acknowledgements idempotent for the exact deleted-device token recorded in a short-lived Relay tombstone; reject unknown and unrelated tokens.
- On an ambiguous 401/403/404 reset response, verify the original device's lifecycle before clearing keys. Surface genuine credential conflicts instead of retrying indefinitely.
- Add explicit **Retry reset** and user-confirmed **Local-only reset** actions. Local-only reset warns that Relay authorization may still need server-side removal; system SMS remain untouched.
- Query official stable GitHub Releases and their versioned update manifest directly from Android, without requiring Relay availability, pairing, Vault, SMS permissions or Device Token.
- Preserve opt-in auto-download, ignored versions, signed-APK certificate pinning, SHA-256 verification, package identity and versionCode anti-downgrade checks.
- Extend Relay deletion/tombstone regression coverage and Android stable-release metadata tests.

## v0.12.1 — Non-default SMS sync, upload visibility and Vault decryption diagnostics

- Register a continuously running SMS Provider ContentObserver while the opt-in foreground Relay service is enabled. Also wake encrypted synchronization on foreground Android SMS Provider changes; keep existing SMS_RECEIVED and bounded history scans as fallback.
- Stop swallowing event encryption/queue errors. Record metadata-only failure categories and upload attempts, last successful Relay ACK time/count, local uploaded-event receipts, pending events and command ACK counts. Keep SMS bodies, addresses and OTPs out of diagnostics.
- Correct the misleading "100 SMS queued" message: distinguish provider records inspected, newly staged events and previously queued/already-uploaded records. A queue count of zero after Relay ACK is normal.
- Fix PWA event loading so a newly uploaded older-dated SMS is not silently discarded. Bootstrap from both recent-by-time and recent-by-sequence windows to account for late imports.
- Show visible, non-destructive Vault/Node Key decryption failure classification and recovery guidance. Missing original Vault or historical Node Keys cannot be recreated from Relay ciphertext.
- Surface broadcast, SMS Provider change, reconciliation and upload checkpoints in Android settings and PWA device details; add server allowlist and browser regression tests.
- Preserve all existing SMS ciphertext, local queue receipts, device keys, pairing, encrypted Relay and Shizuku/Companion functionality. No automatic deletion, re-pair or default-SMS role takeover.

**Field findings:** Android 16 / API 36 with READ_SMS, RECEIVE_SMS, SEND_SMS and Shizuku authorized; latest-100 rescan ran, zero local pending events was observed, while no SMS_RECEIVED broadcast was logged. The earlier blanket failure message did not distinguish Vault/key mismatches from missing SMS. Real vivo/OriginOS live-SMS and old-Vault key recovery still require device-specific verification.

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