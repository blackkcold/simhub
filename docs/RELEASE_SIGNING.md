# Android release signing

SIM Hub never stores the signing private key or passwords in the public repository.

## Canonical signing identity

- Alias: `simhub-release`
- Algorithm: RSA 4096 / SHA256withRSA
- Certificate validity: 2026-10-06 through 2126-09-12
- Certificate SHA-256 fingerprint:
  `63:3F:60:0F:7E:C0:AC:5C:DC:DC:FF:B5:36:AD:E4:BE:EC:83:12:CC:08:34:87:A2:25:C8:C3:08:77:1F:D8:D3`

The fingerprint is public information and is pinned by the signing-verification workflow. The keystore and passwords must remain private.

## Required GitHub Actions secrets

Add these four repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the long-term JKS file.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Repository path:

`Settings → Secrets and variables → Actions → Repository secrets`

The release workflow decodes the keystore only into the ephemeral GitHub Actions runner and sets `ANDROID_KEYSTORE_FILE` for Gradle. The private keystore is never uploaded as a repository artifact.

## Verify after configuration

Run the Actions workflow **Verify Android release signing**. It deliberately checks out the immutable `v0.1.5` tag, builds `assembleRelease`, runs Android `apksigner verify --print-certs`, and fails unless the certificate SHA-256 digest matches the canonical fingerprint above.

With `publish_to_release=true`, the verified APK is also uploaded to the existing `v0.1.5` GitHub Release as:

`simhub-agent-v0.1.5-release.apk`

This allows the signed APK to coexist with the original debug-signed fallback while keeping the source exactly tied to the `v0.1.5` tag.

## Backup requirements

Keep at least two offline backups of:

1. the JKS file;
2. the keystore password;
3. the key alias;
4. the key password.

Losing the signing key prevents a future APK signed with another key from updating installations signed by this key.