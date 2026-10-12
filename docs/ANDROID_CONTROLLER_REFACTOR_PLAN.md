# Android unified workspace and on-device administrator authentication

Status: implementation branch; not released.

## Existing findings (v0.15.5)

- HubApp owns the node Scaffold while ControllerWorkspace owns another top bar, NavigationBar and NavigationRail.
- ControllerSecureClient has Android Keystore P-256 identity and AES-GCM Vault wrapping, but neither key requires local user authentication.
- First-time controller enrollment requires an already unlocked web administrator to approve and ECDH-wrap the existing singleton Vault.
- server/passkeys.py implements WebAuthn verification; Android dependencies include Credential Manager but no native controller credential flow is wired.
- Global Vault must remain singleton; Passkey authentication must not be treated as a Vault decryption key.

## Required implementation

1. Move top bar, mode switch, adaptive bottom/rail navigation into HubApp shared Scaffold. ControllerWorkspace must become a content-only workspace, with four destinations (Home, Messages, Devices, Settings). Preserve each workspace's navigation state.
2. Build reusable node-derived cards, status rows, empty/error/loading views and responsive layouts. Preserve SMS source provenance and per-node key epochs.
3. Integrate Android Credential Manager get/create public key credentials. Bind the Android package and release/debug signing certificates to the HTTPS RP using Digital Asset Links; verify Android origin strictly on the server. Do not bypass origin or user verification.
4. Allow same-phone first enrollment: authenticate administrator using an existing passkey or password plus applicable TOTP, register the local controller public key under that authenticated session, download the existing password-wrapped singleton Vault, locally unwrap with its existing passphrase, verify vaultId/keyId, and seal in Android Keystore. Never create a second Vault.
5. Require user-authentication-bound Keystore protection for foreground Vault unlock. Keep background node SMS synchronization independent of foreground prompts. Server must continue enforcing step-up for sensitive actions.
6. Offer same-device Custom Tabs authentication fallback with one-time, challenge-bound callback; no credentials, Vault key or session cookie in URLs.
7. Preserve server-side revocation, recovery, node role, PWA, existing E2EE history and release upgrade compatibility.

## Security gates

- No passkey-only recovery of the Vault without the Vault passphrase or a separately designed secure recovery envelope.
- No local biometric result alone grants server admin privileges.
- No acceptance of arbitrary RP origins, unverified app signatures, insecure HTTP, or cross-origin redirects.
- Keystore invalidation, device theft, stale grants, TOTP step-up, challenge replay, revoked sessions and incompatible credential providers must be covered.
- Manual verification on supported physical Android versions and foldable layouts is required before a production release.

## Release gate

Increment version only after changes are implemented, CI and unit/integration tests pass, and actual device passkey enrollment, Vault unlock and controller UI smoke tests are confirmed. Do not tag or publish an incomplete implementation.
