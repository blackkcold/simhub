# Android Controller UI and same-device approval — decision record

## Selected implementation

The requirement permits authentication on the Android device **or by navigating to a web
page on the same device**. This release implements the **same-device HTTPS browser route**
and reuses the existing WebAuthn/Passkey + server-side step-up and E2EE Global Vault
approval. Native Credential Manager registration is not claimed or required by this route.

The Android application itself is native Compose and has one shared top bar, mode selector,
four bottom navigation tabs, and one navigation rail. The controller does not embed WebView.

## Completed changes in this branch

- Shared SIM Node / Management Center navigation shell and controller overview tab.
- Local same-device browser entry with optional Passkey/biometrics via the browser's own
  WebAuthn provider; manual secure resume deep link without credentials in its URL.
- Short-lived pairing state persisted as AES-GCM sealed state under Android Keystore, allowing
  recovery after operating-system process eviction while the browser is foregrounded.
- Regression source-contract test added to GitHub Actions CI.

## Security properties preserved

- The singleton Global Vault password and Master Key are never sent to Relay in plaintext.
- Signed controller identity, 6-digit pairing-code confirmation, trusted HTTPS origin,
  TOTP step-up and Vault ID/key ID verification remain mandatory.
- Server-side revocation and key-history / SIM provenance remain unchanged.
- The browser's Passkey authentication is **not** an authentication-bound Android Keystore
  decrypt operation and should never be represented as such.

## Production verification

Release gating requires Android/Relay/Web CI, OTA checks and security scan passing at
the exact merged commit. Physical browser Passkey experience, different OEM biometric
providers, foldable layout and real device process-death recovery require separate
real-device validation.
