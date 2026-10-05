# Security architecture

## Content confidentiality

The relay stores SMS/OTP/contact/outbound text only as AES-256-GCM ciphertext. TLS protects transport, but E2EE means confidentiality does not depend on the reverse proxy or relay database remaining secret.

## Local browser vault

The PWA creates a random 32-byte Vault Key. The browser stores only a wrapped copy:

```text
passphrase
  -> PBKDF2-SHA256 / 310,000 iterations / random salt
  -> AES-GCM wrapping key
  -> encrypted 32-byte Vault Key
```

After unlock, the raw Vault Key lives only in page memory and auto-locks after 15 minutes of inactivity.

## Android local secrets

The Android node uses an AES key generated inside Android Keystore to wrap:

- device bearer token
- Vault Key

The relay URL/device ID are not secrets and remain in normal SharedPreferences.

## Relay authentication

- Controller: high-entropy `SIMHUB_ADMIN_TOKEN`, optionally plus TOTP.
- Android node: independent per-device high-entropy token.
- Relay stores only SHA-256 hashes of device and enrollment tokens.

## Server-readable metadata

Allowed plaintext metadata is intentionally small: event kind, time, device, subscription routing ID, OTP-present boolean, device operational state, queue state and audit actions. The server strips common accidental sensitive fields such as `sender`, `recipient`, `body`, `otp`, `phone` and `contact` from event metadata.

## Recovery and rotation

If the server is compromised, rotate the admin token and re-enroll devices to rotate device bearer tokens. Existing SMS ciphertext remains protected if the Vault Key was not exposed.

If a controller/browser or Android device containing the Vault Key is compromised, create a new Vault and re-enroll nodes. Old relay ciphertext should be treated as decryptable by the compromised key and deleted according to your retention policy.
