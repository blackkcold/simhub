# Relay protocol v1

## Trust boundaries

- **Controller/PWA**: holds the Vault Key while unlocked.
- **Android node**: stores the Vault Key wrapped by Android Keystore.
- **Relay**: never receives the Vault Key through an API endpoint.

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

The plaintext is a JSON object. For `sms.received`, it can include sender, contact name, body, OTP result and subscription ID. Only `hasOtp` is duplicated as relay-readable metadata, never the OTP value.

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

v2 event AAD similarly binds deviceId, eventId, kind, occurredAt, subscriptionId and hasOtp. v2 envelopes include a per-device key identifier (`kid`).

Plaintext contains at least:

```json
{
  "v": 1,
  "action": "sms.send",
  "commandId": "uuid",
  "issuedAt": 1791190000,
  "expiresAt": 1791190120,
  "subscriptionId": 2,
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
