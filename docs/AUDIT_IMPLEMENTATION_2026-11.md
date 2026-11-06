# November 2026 reliability and feature delivery

## Scope and release contract

The repository owner authorized the complete audit remediation and feature plan on
2026-11-06, including incremental GitHub checkpoints and normal signed releases.
Baseline: Stable `e28ddb38eefa11f501faa057fdf2ab6509c2c376`; Dev
`a3406487bf6324bf726d8fcd1084519d45ed9ce2`.

Changes are developed on isolated branches, checkpointed to GitHub, reviewed with
regression evidence, and merged through the existing required checks. Stable keeps
its experimental P2P boundary. No deployment to a user's Relay or physical device
is part of publishing release assets. Tests use isolated, synthetic data.

## Audited defects

| ID | Acceptance criterion | Status |
|---|---|---|
| F1 | Lost POST response and manual retry retain one encrypted SMS intent and command ID | Implemented |
| F2 | Pending token rotation is recovered before authenticated lifecycle requests, including ambiguous expired commits | Implemented |
| F3 | A valid administrator session cannot reset the second-factor attempt budget | Implemented |
| F4 | Revocation terminates command delivery and permits only a scoped cleanup acknowledgement | Implemented |
| F5 | Lock invalidates pending Vault cache writes/restores and wipes sensitive UI | Implemented |
| F6 | SMS manager acquisition supports Android API 29 and 30 | Implemented |
| F7 | Default-handler local SMS works without Relay enrollment using independent local encryption | Implemented |
| F8 | Remote OTA reconciles interrupted stages and cancellation after process death | Implemented |
| F9 | The standalone deployment ZIP supports updater installation and lifecycle preflight | Implemented |
| F10 | Generated proxy configurations and instructions use the selected port/channel | Implemented |
| F11 | Native compose binds stable device/channel/revision identities and preserves encrypted drafts | Implemented |
| F12 | Historical paging cannot advance the contiguous incremental event cursor | Implemented |
| F13 | Location consent/registration is bound to enrollment and suppressed uploads are never reported published | Implemented |
| F14 | Shared history backfill has its own publication/deduplication progress | Implemented |
| F15 | Concurrent ACKs cannot return success without recording the acknowledged state | Implemented |
| F16 | Every static module dependency is available for offline PWA startup | Implemented |
| D1 | Invalid RelayLite queue heads cannot starve valid envelopes (Dev only) | Implemented |
| D2 | RelayLite encrypted reply caches have TTL/capacity and revocation cleanup (Dev only) | Implemented |

## Feature delivery

1. Reliable send center: encrypted persistent drafts/intents, frozen routes,
   reconciliation, explicit ambiguous outcomes, and safe retry of the same command.
2. Multi-SIM workspace: stable source identities, source-aware replies, and encrypted
   alias/phone metadata synchronization without exposing plaintext to the Relay.
3. History/search: explicit coverage, user-controlled backfill, and client-side
   decrypted search with independent incremental and historical cursors.
4. Actionable diagnostics: queue age, last successful activity, retry state, role
   and permission blockers, and appropriate recovery actions.
5. Lifecycle recovery: pairing, credentials, revocation and cleanup states with
   consistent recovery behavior across clients.
6. Updates/backups: recoverable update stages, verified deployment bundles,
   secure backups and repeatable restore verification.
7. Private notifications: safe default-handler SMS notifications, with no duplicate
   Companion notifications or SMS plaintext in public notification surfaces.
8. Dev transport: explicit route state and bounded, non-blocking offline delivery
   while preserving one logical send identity.

## Engineering and verification

- Preserve existing encryption, signing identity, compatibility and database
  rollback guarantees; use additive contracts and retain old-client behavior.
- Add failure/race regression cases for the audited defects; run the complete
  server/modem, Web/browser and Android checks before releasing.
- Strengthen dependency reproducibility, security severity gates, backup handling
  and bounded authentication work without weakening repository rulesets.
- A green emulator or synthetic test does not establish real-carrier delivery or
  OEM behavior. Report those verification boundaries accurately.

## Integration and release record

- Stable changes are checkpointed on `fix/audit-complete-20261106` and reviewed in
  [PR #113](https://github.com/blackkcold/simhub/pull/113).
- Dev uses `fix/audit-dev-integration-20261106` and
  [PR #114](https://github.com/blackkcold/simhub/pull/114). Its merge retains
  SecureHttp/CenterTrust, private CenterConfig inputs and independently approved
  peer sending. Stable does not acquire experimental transport modules.
- Release base version is `0.19.0`, Stable Android version code `56`. Dev keeps
  its existing separately installable package and workflow-generated sequence.
  The release triggers take effect only after protected-branch integration;
  publishing independently waits for successful checks on that exact commit.

## Verification coverage

| Area | Evidence and failure cases |
|---|---|
| Relay and modem | Full discovered Python suites; lost responses, purge/enqueue/poll/ACK races, token recovery, second-factor/KDF bounds, revocation, privacy-suppressed receipts, schema rollback and bounded event batches |
| Web | Full discovered JS suites and Playwright; actual AES-GCM history records, stop/resume, oldest-record query, source-safe folding, reload/lock cleanup, cross-tab send retries and encrypted settings conflicts |
| Android | JVM tests, lint and actual APK metadata builds; API29/30 NodeRecovery instrumentation and API35 isolated HTTPS Relay acceptance, including durable controller journals |
| Dev transport | Mandatory P2P preview and Android sandbox; fair mailbox pulls, negative receipts, bounded encrypted cache, protected mutation claims, frozen send routes and unknown-outcome normalization |
| Deployment | Real standalone ZIP layout, updater success/rollback with mock Docker and real SQLite, private backups, independent restore rehearsal, correct ports, pinned dependency hashes and OCI attestations |
| Security | All six genuine CodeQL jobs and fail-closed SARIF checks; no suppression or severity exception. Installer preflight inspects Linux socket tables and only probes loopback addresses |

Additional review fixes preserve minimal command receipts through SMS deletion,
prevent stale shared-pool results from reappearing after opt-out/reset, and clear
native inbox/search snapshots when enrollment, revocation or SMS purge metadata
changes. Device/SIM display labels never replace authenticated routing identities.

## Operational boundaries

- Web history backfill pauses at 50,000 loaded events or approximately 64 MiB of
  estimated row/text data, with explicit partial coverage. This is not a total
  browser heap cap. Native full-history search streams retained records and
  counts every match while displaying only the newest 1,000 matches. See
  [history/search](HISTORY_SEARCH.md).
- A Relay receipt, queue acceptance or emulator success does not prove physical
  carrier delivery. An uncertain radio outcome remains uncertain; the software
  does not automatically create a replacement SMS.
- Physical SIMs, modem models, OEM power management, real-device permission and
  installation prompts, and sustained hardware operation are external acceptance
  checks. No live phone SMS or production Relay deployment is used for this work.
- Backups with a verified checksum/SQLite integrity result are not labelled as
  restored. The standalone restore verifier performs its own isolated rehearsal;
  the interface distinguishes these facts.
- Remote privacy changes become visible when a client successfully refreshes the
  relevant metadata. Revocation cannot erase plaintext already copied elsewhere.

Final build, scan, emulator and asset evidence is attached to the PR/release
workflow runs. A failed or incomplete required check blocks merging or release;
these implementation statuses do not bypass that verification contract.
