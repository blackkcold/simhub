# Main reliability and UX hardening — 2026-10-15

Baseline: `main@f3bcd706` / v0.17.1. Work is isolated in PR #85; PR #84's P2P transport is explicitly out of scope.

## Implemented software changes

- Version-aware, changed-trigger-verified signed-release activation, without a per-version workflow path.
- Service Worker offline cache ownership and MIME-safe fallback for JavaScript/CSS.
- Android native inbox viewport-constrained conversation scrolling, non-blocking loading states, and bilingual core entry flows.
- Native SMS reply uses the verified sender/recipient address separately from the contact's display name. Do not put a contact display name in the SMS recipient field.
- Locking the native controller invalidates transient older-message page cursors and pending fetch requests.
- Web SMS history does not advance the older-page cursor until ingestion has completed for the current Vault/data generation.
- Additional UI breakpoints and regression checks for Service Worker/offline handling and contact reply addresses.
- Corrected current encrypted-event retention documentation: default `SIMHUB_EVENT_RETENTION_DAYS=0` means no time-based deletion. Nonzero retention is an opt-in.
- Dependency update coverage extended for Python and Docker.

## Explicitly unverified / out-of-scope

- Android OEM/Doze/dual-SIM/foreground-service behavior, physical SMS delivery and actual system notifications need physical-device testing as documented in `HARDWARE_VALIDATION.md`.
- OS user-auth-bound Keystore rewrap needs a separately validated recovery/migration scheme before activation. The existing biometric gate must not be described as cryptographic hardware key authentication.
- Closed-browser Web Push is **not implemented**. Browser permission, a Service Worker registration and a successful `showNotification` call do not guarantee background delivery or actual OS display. Existing server-side metadata-only webhook/ntfy/Bark paths can be configured independently.
- Google Play SMS/Special Use permission policy and physical-device certification require external approvals and tests.
- Mandatory CI branch-rule enforcement requires a repository administration action on the active ruleset; a passing GitHub Actions job alone is not a merge restriction.
- P2P / NAT traversal / PR #84 remains independent.

## Release gate

1. PR #85 CI, CodeQL Java/Kotlin/Python/JavaScript, and Remote OTA pre-release checks must pass on the final head commit.
2. A release must be published only from reviewed `main` with matching Android/Relay version and matching changed version trigger.
3. Verify a signed Android APK, OCI digest and update manifest; do not claim hardware acceptance from CI.
4. Validate one real Android SMS node and one controller before declaring an unattended-production deployment.

No E2EE format, Node/Vault key semantics, protocol routing, P2P implementation or automatic ciphertext deletion is intentionally changed by this PR.
