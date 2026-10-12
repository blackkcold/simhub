# Global Vault · v0.15.5

## Single logical Vault, independent device cryptography

One Relay deployment has exactly one `vault_id` and one random 32-byte Master Vault Key. The Relay stores only a password-wrapped encrypted copy in `global_vault`, alongside the non-secret Vault ID, SHA-256 truncated key fingerprint, KDF salt, AES-GCM nonce and revision.

Web/PWA clients download the **same encrypted envelope** after administrator authentication and locally unwrap with the **existing Vault passphrase**. An administrator's server login password is intentionally separate from the Vault passphrase. No per-browser Vault creation is offered once the Relay has been initialized.

Each SMS Agent still has an independently randomized Node Key. The Master Key is used client-side to unwrap the current, pending and retired Node Key envelopes for all enrolled devices. This preserves blast-radius isolation when one Android node is compromised. The Shared SMS Pool remains a separate opt-in device-to-device sharing feature; administrator access does not depend on enabling it.

## First deployment and existing data

1. Log into the **oldest trusted Web/PWA management browser**, which still retains the original `simhub_vault_v1` record. If no Global Vault exists yet, choose **首次初始化共享 Vault** and input that browser's original Vault passphrase.
2. The client first tries to unwrap the old local Master Key and registers the **same raw Master Key**, password-wrapped, as the singleton in the Relay; it does not generate a second root. The old browser entry is deleted **only after** the Relay confirms registration.
3. All other browsers log into the same management origin and enter the **original Vault passphrase**, without importing the recovery key or creating another Vault.
4. A new Android management terminal is approved by an unlocked, authenticated browser. The encrypted ECDH grant binds to the Global Vault ID and key fingerprint; returning Android controllers verify this identity when opening a signed session.
5. If no usable existing browser Vault remains, initialize a fresh random root. Previously encrypted records may become unreadable; re-pair mismatched Agents and re-sync retained original SMS from Android. Do not mistake the Relay event count for successful plaintext decryption.

Only one writer can initialize the singleton (SQL primary key and unique Vault ID). Password changes use `POST /api/v1/vault/rewrap` with generation compare-and-swap, and **do not** change the raw Master Key or Agent Node Keys.

## API, boundaries and recovery

Authenticated endpoints: `GET /api/v1/vault`, `GET /api/v1/vault/status`.
Step-up-protected endpoints: `POST /api/v1/vault/initialize`, `POST /api/v1/vault/rewrap`.
The Relay validates envelope structure, KDF costs and ciphertext sizes, but cannot test a guessed passphrase because it has no plaintext key.

An offline `SIMHUB-RECOVERY-V1:` recovery key can unlock the *same* Global Vault only if its derived fingerprint matches the registered key. Recovery import cannot silently overwrite another Vault or alter Agent envelopes. A backup must preserve the Relay database (including wrapped historical Node Keys and Pool Key epochs) and the offline recovery key.

Security considerations:
- The encrypted Master Key is obtainable by any authenticated admin client and is subject to **offline password guessing** if the server/database is compromised. Use a high-entropy, distinct Vault passphrase; login/Passkeys/TOTP do not replace its confidentiality.
- PBKDF2-SHA256 remains the interoperable KDF for migration and current browser compatibility. Increasing iterations is supported through envelope metadata; a future memory-hard KDF requires clients and migration support first.
- Browser trusted snapshots are convenience mechanisms, not hardware-backed, and do not resist malicious same-origin JavaScript. Native Android uses Android Keystore protection.
- Controller grants must be separately authorized and can be revoked; copying a controller configuration QR never discloses any secret.
- If legacy v1 message ciphertext belongs to an unrelated, lost Master Key, no server-side operation can reconstruct its plaintext.

## Acceptance

Run `PYTHONPATH=server python3 -m unittest -v server.test_global_vault server.test_controller_auth` plus existing Relay/Web/Android CI. Browser UI regression covers new isolated browser profile unlocking the existing root without old local storage. Physical Android re-pairing, background SMS sync and deployment-specific hardware must be verified against real devices.

