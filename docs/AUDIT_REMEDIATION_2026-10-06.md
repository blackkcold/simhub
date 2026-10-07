> **Update (v0.2.1):** This historical checkpoint is superseded by the v0.2.1 hardening release. New enrollment packages no longer contain plaintext Node Keys; Android release signing is now mandatory for releases; modem multipart receive is durably reassembled.

# SIM Hub audit remediation — 2026-10-06

Branch: `hardening/node-channel-modem`  
Target: `main`  
Scope: Android Agent, relay server, PWA, Docker/operations, Linux/DJI Modem Agent and CI.

This document is the durable checkpoint for the repository audit. Android signing is intentionally excluded from this remediation.

## Product target

SIM Hub is a multi-node SMS control plane:

```text
Controller / PWA
      |
      | E2EE events + commands
      v
Personal Relay
      |
      +-- Android Node -> one or more SIM/eSIM Channels
      |
      +-- Linux Modem Node -> DJI / Quectel / generic modem Channel
```

Required capability: reliable SMS receive/view/send through the user's relay.  
Optional capability: carrier/radio/signal telemetry.

## Remediation status

| Finding | Status | Implementation |
|---|---|---|
| JobService created/leaked an executor per run | Fixed | Shared bounded `AgentExecutors`; JobService tracks/cancels futures |
| Live SMS queue failure could silently lose relay event | Fixed | Queue failure diagnostic + automatic SMS Provider reconciliation on every sync |
| Multipart callbacks were counter-based, not per-part idempotent | Fixed | `sms_part_status(command_id, part_index)` |
| Delivery callback result code was not interpreted | Fixed | delivery success/failure stored per part; failed delivery emits failed ACK/event |
| Pending SMS callback state could remain forever | Fixed | stale tracking expires without resending the SMS |
| Android subscriptionId treated as durable business identity | Fixed | stable `channelId + revision`; stale/replaced SIM commands fail closed |
| Detailed radio metrics missing | Fixed | Android LTE/NR dBm/RSRP/RSRQ/SINR when available |
| Master Vault Key distributed to every new node | Fixed for new nodes | random independent Node Key per node |
| Legacy nodes need safe migration from master-derived key | Fixed | pending wrapped key + encrypted `node.rotate_key` + promote only after node reports new `cryptoKeyId` |
| Pending key rotation could be overwritten after browser refresh | Fixed | single-flight pending key + controller-visible Master-wrapped pending envelope |
| Android local pending-SMS state depended on traffic key | Fixed | separate local queue key protected by Android Keystore |
| Relay model was Android-specific | Fixed | Generic Node + Channel projection, nodeType/capabilities |
| DJI / USB modem path missing | Implemented | Linux Modem Agent; ModemManager/mmcli + external dji4g adapter |
| Modem local reliability missing | Implemented | SQLite event/command state queue, idempotent command claim, backoff |
| Modem SMS storage could fill indefinitely | Fixed | durable local queue/seen mark before best-effort delete/archive from modem |
| Device bearer token effectively permanent | Fixed | automatic 60-day two-phase prepare/commit rotation on Android and Modem Agent |
| Docker build context could include secrets/history/build output | Fixed | root `.dockerignore` |
| Reverse proxy IP handling used only socket peer | Fixed | trusted CIDR allowlist + forwarded IP only from trusted peer |
| OTA admin path bypassed common admin auth/rate-limit | Fixed | routes through `require_admin()` unless device-authenticated |
| Event/audit retention happened only at startup | Fixed | continuous maintenance loop |
| Audit/command retention not independently configurable | Fixed | separate retention settings |
| Health endpoint only proved process liveness | Fixed | `/readyz` checks DB/schema; Docker uses readiness |
| TOTP was silently optional in sample deployment | Hardened | new example requires TOTP; relay refuses startup when requirement=true and secret missing |
| CI only proved Android compilation | Improved | server behavior/migration/maintenance tests, Modem crypto/queue tests, Android JVM OTP tests + API 37 build |
| Phone-call functionality | Intentionally absent | no dialer/call/SIP/PSTN scope added |
| Android release signing | Fixed in v0.2.1 | mandatory release signing with pinned certificate; no debug fallback |

## Security model after remediation

### Master Vault

The Controller owns the Master Vault Key.

New nodes never receive it.

For each node:

1. Controller generates a random 32-byte Node Key.
2. Controller encrypts/wraps that Node Key with the Master Vault Key.
3. Relay stores only the wrapped envelope and public key ID.
4. v0.2.1 enrollment packages contain only a one-time Bootstrap Secret; the Node Key is returned only as bootstrap-encrypted ciphertext and decrypted locally.
5. Node traffic uses AES-256-GCM v2 with metadata-bound AAD.

Compromise of one new node therefore does not permit derivation of sibling Node Keys.

### Legacy migration

Legacy v0.1.x nodes may temporarily retain the previous Master-derived key. The PWA can queue a new independent key:

1. Relay stores it as a pending Master-wrapped envelope.
2. Controller sends `node.rotate_key` under the old trusted channel.
3. Node refuses rotation while an outbound SMS is pending.
4. Node stores the independent key and deletes legacy Master material.
5. Node reports the new `cryptoKeyId`.
6. Relay promotes pending -> active only after that confirmation.

### Authentication token rotation

Bearer authentication and E2EE content keys are separate.

Node bearer token rotation:

1. current token authenticates `token/prepare`;
2. relay creates a one-hour pending token while old token remains valid;
3. node durably stores the new token;
4. new pending token authenticates `token/commit`;
5. relay atomically promotes it.

Android and Modem Agent initiate this automatically after 60 days.

## Channel model

A Channel is the stable remote routing target.

Examples:

- Android SIM/eSIM: `kind=android-sim`, `localId=<subscriptionId>`
- Linux/DJI modem: `kind=cellular-modem`, `localId=sim0`

Remote `sms.send` contains:

- node ID;
- channel ID;
- channel revision;
- destination/body inside E2EE ciphertext.

If the local SIM fingerprint changes, revision increments. A command with an old revision is rejected instead of being sent through a replacement SIM.

## DJI / modem architecture

The relay does not know DJI-specific AT commands.

```text
DJI / Quectel / generic USB modem
       |
       +-- dji4g CLI adapter (DJI Gen1/QDC507 path)
       |
       +-- ModemManager/mmcli adapter
       |
Linux Modem Agent
       |
same SIM Hub E2EE Node/Channel protocol
```

This avoids binding the core server/PWA to one hardware vendor.

DJI Cellular Dongle 2 remains capability-gated until actual hardware confirms a supported SMS interface.

## CI-covered gates

- Relay API tests.
- v0.1.x -> current DB migration test.
- Retention/maintenance regression test.
- Node/Channel projection.
- Independent/pending Node Key metadata flow.
- Trusted-proxy audit IP behavior.
- Crash-safe bearer-token rotation.
- Modem AES-GCM/AAD round trip.
- Modem local queue/idempotency.
- UCS2 helper behavior.
- Web JavaScript syntax.
- Python compile.
- Modem install-shell syntax.
- Android JVM OTP tests.
- Android API 37 debug APK build.

## Physical hardware gates still required

These cannot be truthfully marked passed by GitHub CI.

### Android

- Android 17 physical default-SMS receive.
- Real OTP carrier messages.
- Multipart receive/send with carrier callbacks.
- Delivery-report behavior by carrier.
- Dual SIM receive/send.
- Physical remove/reinsert/replace SIM and Channel revision check.
- Reboot/unlock/autostart.
- Doze and OEM battery restrictions for 24–72 hours.
- Network/relay outage then Provider reconciliation.
- vivo/OPPO/Xiaomi/Huawei/HONOR target ROM behavior.

### Modem / DJI

- Actual USB enumeration.
- Adapter selection.
- Real SMS receive.
- Durable queue -> relay upload -> modem SMS deletion.
- Remote SMS send.
- Signal/RAT metrics.
- Unplug/replug recovery.
- SIM replacement -> Channel revision increment.
- DJI Cellular Dongle 2 capability verification if that model is to be supported.

## Deployment recommendation

After CI is green, merge the hardening branch and deploy relay/PWA first. Upgrade/re-enroll nodes incrementally.

For an existing v0.1.5 Android node:

1. upgrade Agent;
2. verify Channel state;
3. use Controller `Isolate key`;
4. wait until relay reports the new active key ID;
5. test receive/send;
6. only then treat the node as migrated.

For a new Android or Modem node, independent Node Keys are used from first enrollment.
