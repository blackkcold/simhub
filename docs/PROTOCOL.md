# Relay protocol v1

## Trust boundaries

- **Controller/PWA**: holds the Master Vault Key while unlocked and unwraps per-node Node Keys locally.
- **Android node / Linux Modem Agent**: stores only its own independent Node Key.
- **Relay**: stores only wrapped Node Key envelopes and never receives the Master Vault Key or Node Keys in plaintext.

## Enrollment

### v4 bootstrap enrollment

New v0.2.1 nodes use a one-time bootstrap exchange so the enrollment link/package never contains the long-term Node Key.

1. Controller generates a random 32-byte Node Key.
2. Controller generates a separate random 32-byte Bootstrap Secret.
3. Controller wraps the Node Key twice:
   - once with the Master Vault Key for long-term controller recovery;
   - once with the Bootstrap Secret using AES-256-GCM and AAD `simhub-bootstrap-node-key-v1|<kid>`.
4. Controller creates the enrollment token on the relay, storing only ciphertext envelopes, the public key ID, and `SHA-256(Bootstrap Secret)` as a non-secret proof digest.
5. The Android deep link / Modem JSON contains only:
   - relay URL;
   - one-time enrollment token;
   - one-time Bootstrap Secret;
   - node label/type.
6. Before consuming the token, the node sends `SHA-256(Bootstrap Secret)` as `bootstrapProof`; the relay compares it in constant time with the stored digest. A wrong proof fails without consuming the token.
7. On a valid proof, Relay atomically creates the device, marks the token used, returns the encrypted bootstrap envelope, and clears both bootstrap ciphertext and proof digest from the enrollment row.
8. Node decrypts the Node Key locally, verifies its `kid`, then stores it using Android Keystore-protected storage or the mode-0600 Linux Agent config.

The Relay never receives the Bootstrap Secret or plaintext Node Key. After enrollment is consumed, the old enrollment link/package is insufficient to recover the Node Key.

Enrollment tokens remain short-lived and single-use.

Legacy v3 enrollment links that directly contain a Node Key remain readable only for rolling-upgrade compatibility; new enrollment creation requires the v4 bootstrap envelope.

## Event encryption

AAD: `simhub-event-v1`

```json
{
  "v": 1,
  "alg": "A256GCM",
  "iv": "base64url-12-byte-nonce",
  "ct": "base64url-ciphertext-plus-tag"
}
```

The plaintext is a JSON object. For `sms.received`, it can include sender, contact name, body, OTP result and Channel identity. Only `hasOtp` is duplicated as relay-readable metadata, never the OTP value.

## Command encryption

v1 compatibility AAD: `simhub-command-v1`

v2 command AAD:

```text
simhub-command-v2 |
base64url(deviceId) |
base64url(commandId) |
base64url(type) |
createdAt |
expiresAt |
base64url(idempotencyKey)
```

v2 event AAD binds deviceId, eventId, kind, occurredAt, the relay routing field (Channel ID for 0.2 nodes; legacy subscription ID for older events) and hasOtp. v2 envelopes include the active Node Key identifier (`kid`).

Plaintext contains at least:

```json
{
  "v": 1,
  "action": "sms.send",
  "commandId": "uuid",
  "issuedAt": 1791190000,
  "expiresAt": 1791190120,
  "channelId": "57d8…",
  "channelRevision": 3,
  "to": "+86...",
  "body": "..."
}
```

The relay sees `type=sms.send`, ID, device routing and expiry. It cannot read destination or body. Android verifies AES-GCM authenticity and that the encrypted `action`/`commandId` match the relay envelope before execution.

## Replay and duplicates

- Server enforces unique event IDs **per device** and command idempotency keys per device.
- Device atomically claims command IDs before side effects, persists command state for 30 days, and durably queues command acknowledgements.
- Commands expire server-side and device-side.
- SMS Provider row IDs are reused as event IDs when possible so history re-sync does not duplicate live SMS.


## Browser session authentication

The PWA posts the Admin Token and optional TOTP to `POST /api/v1/auth/session`. The relay stores only a hash of the generated random session token and sets a Secure/HttpOnly/SameSite=Strict cookie. Subsequent PWA API calls and the SSE endpoint use that session cookie; the Admin Token is not persisted in browser localStorage. Direct Bearer + TOTP authentication remains available for trusted CLI/admin integrations.


## Generic channel binding

0.2 nodes expose a stable Channel identity:

```json
{
  "id": "channel-uuid",
  "localId": "7",
  "kind": "android-sim",
  "revision": 2
}
```

A remote `sms.send` command contains both `channelId` and `channelRevision`. Android resolves that Channel to the current local `subscriptionId`; a Linux modem resolves it through its adapter. If the SIM fingerprint changed and the revision no longer matches, the command is rejected with `subscription_changed` / `channel_changed`.

## Independent Node Key migration

New nodes are provisioned directly with independent random Node Keys. For a legacy v0.1.5 Android node:

1. Controller generates a new Node Key and stores its Master-wrapped envelope on the relay as `pending`.
2. Controller sends `node.rotate_key` encrypted under the existing legacy-derived key.
3. Android refuses rotation while an outbound SMS is still pending.
4. Android stores the new Node Key, deletes the legacy Master Vault Key, and reports the new `cryptoKeyId`.
5. Relay promotes the pending wrapped Node Key only after that confirmation.

This makes rotation fail-safe across network or process interruption.

## Device bearer-token rotation

Device authentication tokens use a separate two-phase rotation:

1. authenticated node calls `POST /api/v1/devices/{id}/token/prepare`;
2. relay creates a pending token while leaving the current token active;
3. node stores the new token locally;
4. node calls `POST /api/v1/devices/{id}/token/commit` with the pending token;
5. relay atomically promotes it.

If the process crashes between steps 3 and 4, the node retains the pending token locally and retries only the commit on the next sync cycle.

## v0.4.0–v0.5.0 command and session extensions

The event/command AES-GCM envelope and Node-Key trust boundary remain backward-compatible. Controller-facing improvements do **not** change the SMS plaintext visibility of the Relay:

| Message | Purpose | Behavior |
|---|---|---|
| `sms.sync_recent` | Explicit newest-message rescan | Android Agent v0.5.0+ scans **up to 100** latest provider records; event IDs deduplicate history |
| `sms.sync_older` | Explicit older backfill | Android Agent v0.5.0+ advances separate history cursor, **up to 100** per command |
| `sms.sync_history` | Rolling-upgrade alias | Preserved for older Android versions; semantically means older backfill, **not all messages** |
| `diagnostics.request` | Health snapshot | Node queues a Node-Key-encrypted `device.diagnostics` event; v0.5.0 adds request-ID correlation |
| `device.network_policy` | Android fallback intent | Stores desired SIM channel/revision and reports whether it equals Android's **system-default data SIM**; **does not toggle cellular data or switch default subscription** |

The device state may contain `encryptedSimNumbers`: an `eventId`, `occurredAt`, and Node-Key-encrypted `ciphertext` holding the array of observed SIM phone numbers. The server validates and stores the opaque envelope only; it **does not receive the plaintext telephone numbers**. The Web controller may save Vault-encrypted manual number overrides in its own browser storage, outside the Relay database.

The Web Inbox's older-message pagination is a separate Controller-to-Relay read path; it never invokes Android SMS history scanning. Results from history commands indicate items **queued for upload**, not guaranteed server persistence or end-of-history truth.

Administrator session authentication supports usernames, optional Passkeys and authenticated `/api/v1/auth/check` calls. The default session inactivity deadline is 8 hours and absolute lifetime 24 hours. Browser Vault recovery across refresh is entirely local and is permitted only after a valid server session check; a Passkey is not a substitute for the Vault recovery key.
