# Security policy

## Do not put secrets in Git

Never commit `.env`, admin tokens, TOTP secrets, Android signing keys, device tokens, vault keys, SMS exports, database files, or production server URLs containing credentials.

## Report a vulnerability

For a public fork, use GitHub private vulnerability reporting if enabled. Do not open a public issue containing secrets or exploit payloads against a live installation.

## Security invariants

1. SMS/OTP plaintext is encrypted on the Android node before relay upload.
2. Remote SMS command plaintext is encrypted in the controller before relay submission.
3. The relay never receives the Vault Key during normal API use; v2 traffic uses a per-device HKDF-derived key and a visible `kid` only.
4. Device bearer tokens are stored only as SHA-256 hashes on the relay.
5. Enrollment tokens are single-use, short-lived, and stored as hashes.
6. Commands have an ID, expiry and idempotency key; Android atomically claims commands before side effects, persists state and durably queues ACKs.
7. Browser admin credentials are exchanged for a short-lived Secure/HttpOnly/SameSite session; the PWA does not store the Admin Token in localStorage.
7. Logs and audit rows never contain SMS body, OTP or outbound SMS text.
8. HTTP is unsupported for normal operation. Terminate TLS at Caddy/Nginx/Traefik.

## Public-repository checklist

- Enable GitHub secret scanning and push protection.
- Require pull requests for protected branches if more than one person contributes.
- Keep production signing in a hardware-backed or CI-protected secret store.
- Rotate `SIMHUB_ADMIN_TOKEN` and revoke all device tokens after suspected server compromise.
- Re-enroll devices with a newly created vault after suspected controller compromise.
