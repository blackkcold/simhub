# Android release signing

SIM Hub never stores the signing private key or passwords in the public repository.

## Canonical signing identity

- Alias: `simhub-release`
- Algorithm: RSA 4096 / SHA256withRSA
- Certificate validity: 2026-10-06 through 2126-09-12
- Certificate SHA-256 fingerprint:
  `63:3F:60:0F:7E:C0:AC:5C:DC:DC:FF:B5:36:AD:E4:BE:EC:83:12:CC:08:34:87:A2:25:C8:C3:08:77:1F:D8:D3`

The fingerprint is public information and is pinned in both the manual signing-verification workflow and the tag-driven release workflow.

## Required GitHub Actions secrets

Repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore is decoded only into the ephemeral Actions runner. The private JKS is never uploaded as a repository or release artifact.

## v0.2.1+ release policy

A release tag such as `v0.2.1` triggers `.github/workflows/release.yml`.

The workflow:

1. verifies the tagged commit belongs to `main` history;
2. verifies the tag version equals Android `versionName` and relay `APP_VERSION`;
3. runs the full server/web/modem/Android test suite;
4. fails immediately if any signing secret is missing;
5. builds `assembleRelease`;
6. runs `apksigner verify --print-certs`;
7. fails unless the signing certificate matches the pinned SHA-256 fingerprint;
8. uploads the signed APK to a separate artifact;
9. creates the GitHub Release from a publish job that does not receive the keystore secrets.

There is **no debug-signed fallback** for v0.2.1 and later releases.

## Manual verification

The **Verify Android release signing** workflow always checks out `main`, builds the signed release APK and verifies the same pinned certificate. It does not accept an arbitrary branch/ref input, reducing the risk of running modified build logic with signing secrets.

## Backup requirements

Keep at least two offline backups of:

1. the JKS file;
2. the keystore password;
3. the key alias;
4. the key password.

Losing the signing key prevents future APKs signed with another key from updating installations signed by this identity.
