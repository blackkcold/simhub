# Test-only TLS identity

These two self-signed P-256 certificates and their keys exist only so the JVM unit
tests and the public CI configuration generator have a real TLS identity to pin.

They are **not** deployment material:

- no system, store, device or CI trust store contains them;
- no production origin resolves to them;
- they are pinned only from within tests, where the trust anchor is injected on top of
  `SecureHttp.pinned(...)` so a handshake can complete. Production never widens the
  platform trust store;
- `center-test-backup-cert.pem` exists to act as a *wrong* pin for the same host, which
  is what lets `SecureHttpTrustTest` prove that a valid certificate chain with an
  unexpected public key is rejected before any credential is written.

Public CI derives the fake trusted-center description from these certificates so the
generator, loader and pin parser run along the same path as a private packaging build.
Regenerating them changes the fake pins and is harmless, but the two pins must stay
distinct.
