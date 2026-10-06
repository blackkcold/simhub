# Relay protocol v1

## Trust boundaries

- **Controller/PWA**: holds the Master Vault Key while unlocked and unwraps per-node Node Keys locally.
- **Android node / Linux Modem Agent**: stores only its own independent Node Key.
- **Relay**: stores only wrapped Node Key envelopes and never receives the Master Vault Key or Node Keys in plaintext.

## Enrollment

1. Controller asks relay for a short-lived one-time token.
2. Controller builds a local-only deep link containing relay URL + one-time token + 256-bit Vault Key.
3. Android posts the enrollment token to the relay.
4. Relay returns a device ID and high-entropy device token.
5. Android stores the device token and Vault Key using Android Keystore-backed local encryption.

The enrollment link must be treated as a temporary secret.

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
