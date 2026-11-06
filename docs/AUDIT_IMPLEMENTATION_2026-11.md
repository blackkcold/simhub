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
| F1 | Lost POST response and manual retry retain one encrypted SMS intent and command ID | In progress |
| F2 | Pending token rotation is recovered before authenticated lifecycle requests, including ambiguous expired commits | In progress |
| F3 | A valid administrator session cannot reset the second-factor attempt budget | In progress |
| F4 | Revocation terminates command delivery and permits only a scoped cleanup acknowledgement | In progress |
| F5 | Lock invalidates pending Vault cache writes/restores and wipes sensitive UI | In progress |
| F6 | SMS manager acquisition supports Android API 29 and 30 | In progress |
| F7 | Default-handler local SMS works without Relay enrollment using independent local encryption | In progress |
| F8 | Remote OTA reconciles interrupted stages and cancellation after process death | In progress |
| F9 | The standalone deployment ZIP supports updater installation and lifecycle preflight | In progress |
| F10 | Generated proxy configurations and instructions use the selected port/channel | In progress |
| F11 | Native compose binds stable device/channel/revision identities and preserves encrypted drafts | In progress |
| F12 | Historical paging cannot advance the contiguous incremental event cursor | In progress |
| F13 | Location consent/registration is bound to enrollment and suppressed uploads are never reported published | In progress |
| F14 | Shared history backfill has its own publication/deduplication progress | In progress |
| F15 | Concurrent ACKs cannot return success without recording the acknowledged state | In progress |
| F16 | Every static module dependency is available for offline PWA startup | In progress |
| D1 | Invalid RelayLite queue heads cannot starve valid envelopes (Dev only) | In progress |
| D2 | RelayLite encrypted reply caches have TTL/capacity and revocation cleanup (Dev only) | In progress |

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

The final update to this document records delivered commits, tests and remaining
environmental verification limits before the release trigger is merged.
