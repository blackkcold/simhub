# Security policy

## Do not put secrets in Git

Never commit `.env`, admin tokens, TOTP secrets, Android signing keys, device tokens, vault keys, SMS exports, database files, or production server URLs containing credentials.

## Report a vulnerability

For a public fork, use GitHub private vulnerability reporting if enabled. Do not open a public issue containing secrets or exploit payloads against a live installation.

## Security invariants

1. SMS/OTP plaintext is encrypted on the Android node before relay upload.
2. Remote SMS command plaintext is encrypted in the controller before relay submission.
3. The relay never receives the Master Vault Key or plaintext Node Keys. New nodes use independent random Node Keys; relay persistence contains only Master-wrapped Node Key envelopes and a visible `kid`.
4. Device bearer tokens are stored only as SHA-256 hashes on the relay.
5. Enrollment tokens are single-use, short-lived, and stored as hashes.
6. Commands have an ID, expiry and idempotency key; Android atomically claims commands before side effects, persists state and durably queues ACKs.
7. Browser admin credentials are exchanged for a short-lived Secure/HttpOnly/SameSite session; the PWA does not store the Admin Token in localStorage.
8. Device bearer tokens rotate every 60 days through a crash-safe prepare/commit flow; the relay stores hashes only.
9. Remote SMS commands bind a stable Channel ID and revision so SIM replacement cannot silently redirect an old command.
7. Logs and audit rows never contain SMS body, OTP or outbound SMS text.
8. HTTP is unsupported for normal operation. Terminate TLS at Caddy/Nginx/Traefik.

## Public-repository checklist

- Enable GitHub secret scanning and push protection.
- Require pull requests for protected branches if more than one person contributes.
- Keep production signing in a hardware-backed or CI-protected secret store.
- Rotate `SIMHUB_ADMIN_TOKEN` and revoke all device tokens after suspected server compromise.
- Re-enroll devices with a newly created vault after suspected controller compromise.
