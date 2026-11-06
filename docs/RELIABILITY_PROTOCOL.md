# Recovery and encrypted settings contracts

These additive contracts preserve existing client fields. The Relay never receives
SMS plaintext, the Global Vault key, decrypted aliases, or phone overrides.

## Command reconciliation

`GET /api/v1/devices/{deviceId}/commands/{commandId}` uses the same administrator
or native-controller session authentication as `/api/v1/commands/recent`.

```json
{
  "command": {
    "commandId": "immutable-client-command-id",
    "deviceId": "enrollment-id",
    "type": "sms.send",
    "createdAt": 1700000000,
    "expiresAt": 1700000120,
    "ackAt": null,
    "state": "queued",
    "result": {}
  }
}
```

The response excludes ciphertext. A missing record returns HTTP 404 with
`error: "command_not_found"`. Expired retention or complete device deletion can
remove records; 404 is not proof that an old or expired SMS was never executed. Persist the
original command ID, idempotency key, envelope, target device/channel/revision and
timestamps before POST. Reconcile that ID after an ambiguous response. Only a
still-valid original envelope may be retried; uncertain execution never creates
a replacement SMS command automatically.

An SMS-history purge retains metadata-only command tombstones through the later
of the original expiry or purge time, plus the configured command retention
period (at least one day). Exact-ID reads return `purged: true`; matching enqueue
retries return the original disposition with `duplicate: true` and
`enqueued: false`. No ciphertext, recipient, message or result payload survives
in a tombstone. A late authenticated ACK may advance its state without restoring
content. Queued work is rejected; already dispatched/submitted work remains
`outcome_unknown` unless an actual receipt establishes the result. The additive
SQLite insertion fence also prevents older Relay binaries from silently reusing
these identities during a code-only rollback.

ACK transitions serialize with the current state and return
`{ok: true, currentState, ackAt}` after commit. Duplicate or stale transitions add
`duplicate: true` or `ignored: true`. Already dispatched commands become
`outcome_unknown` on expiry, revocation or reset; these states do not establish
that the radio did not send the message.

## Token and lifecycle recovery

Resolve the pending token journal before any ordinary authenticated request,
including `/lifecycle`. Repeat `/token/commit` with the pending credential even
after its local prepare expiry: the Relay may already have committed it. An
idempotent success returns the original `tokenIssuedAt`. Persist the new active
credential before discarding the journal.

A recognized credential on a revoked/reset node receives HTTP 409
`device_reset_pending` from token commit. `/lifecycle` remains cleanup-only:
`{resetRequired: true, status: "revoked" | "reset_pending"}`. The exact current
credential, or an unexpired pending credential while cleanup is requested, may
acknowledge `/reset`. Revocation-only cleanup returns
`{ok: true, status: "revoked", resetRequired: true}` and retains administrator
history. Explicit reset/delete removes the record and leaves the existing
bounded, credential-specific HTTP 410 tombstone for retried cleanup receipts.

No lifecycle/cleanup credential regains event, command, heartbeat or key-rotation
rights. Long-poll dequeue rechecks credential and lifecycle under the write lock.
In-flight work already dispatched before a revocation may still complete.

An offline/ambiguous response retains both credentials. Discard an invalid
pending credential only after an explicit rejection and proof that the old
credential is still recognized. A local prepare deadline alone is insufficient.

## Event upload receipts

Single and batch receipts provide the same disposition:

| `receiptState` | Meaning | `serverSequence` | Existing flags |
|---|---|---|---|
| `stored` | A new encrypted event was stored | Positive integer | `accepted: true` |
| `duplicate` | This event identity is already stored | Existing positive integer | `accepted: true`, `duplicate: true` |
| `suppressed` | A privacy/retention fence intentionally rejected publication | `0` | `accepted: true`, `suppressed: true` |

All three may retire the matching queued upload. Only `stored` and `duplicate`
confirm publication. For older Relays, derive the disposition from the existing
boolean fields and sequence. Batch responses retain `{accepted: true, results:
[...]}` and each result includes its exact `eventId`. A missing or malformed
receipt never acknowledges a different local item.

## Encrypted aliases and phone overrides

`GET /api/v1/vault/settings` returns:

```json
{
  "initialized": true,
  "vaultId": "existing-global-vault-uuid",
  "keyId": "existing-global-vault-key-id",
  "revision": 0,
  "updatedAt": null,
  "ciphertext": null
}
```

Revision zero means no settings envelope exists. `POST` to the same route uses a
normal authenticated administrator/native-controller session plus CSRF; it does
not require another step-up for editing labels. The exact request fields are:

```json
{
  "vaultId": "existing-global-vault-uuid",
  "keyId": "existing-global-vault-key-id",
  "expectedRevision": 0,
  "ciphertext": {
    "v": 2,
    "alg": "A256GCM",
    "kid": "existing-global-vault-key-id",
    "iv": "base64url-without-padding",
    "ct": "base64url-without-padding"
  }
}
```

Encrypt on the client with the existing Global Vault key. The 12-byte IV is new
for every write. AAD is UTF-8 `simhub-vault-settings-v1|` followed by the exact
Vault UUID. The plaintext schema is
`{v: 1, phoneOverrides: {}, channelAliases: {}}`; map keys are the stable
`deviceId|channelId|revision` identity. Coordinates, consent and authentication
secrets do not belong in this settings object.

The Relay accepts only these envelope fields, verifies the current Vault/key
identity and caps the encoded ciphertext at 60,000 characters. It cannot inspect
the decrypted maps. Success returns the same shape as GET with an incremented
revision. Concurrent writes or changed Vault identity return HTTP 409
`settings_conflict`; malformed envelopes/extra plaintext fields return HTTP 400
`invalid_settings`. Refresh, decrypt and merge on the client before retrying a
conflict. Password rewrap keeps the same Vault key and settings revision.

## Authentication work budgets

Login and step-up verification reserve bounded attempts before checking a factor.
Step-up budgets are independent of ordinary valid-session traffic and are scoped
by both IP and session. Only verification of the matching factor clears its
budget. Argon2 work has a separate nonblocking semaphore configured by
`SIMHUB_MAX_KDF_CONCURRENCY` (default 2, range 1–8); capacity exhaustion returns
HTTP 429 `authentication_busy`. Operators increasing concurrency must budget for
the configured 64 MiB memory cost per password operation.
