# Android release signing

SIM Hub never stores a signing private key in the public repository.

To make GitHub Releases produce a stable release-signed APK, add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the `.jks`/keystore file.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The release workflow decodes the keystore only into the ephemeral GitHub Actions runner and sets `ANDROID_KEYSTORE_FILE` for Gradle. The keystore is not uploaded as an artifact.

If any required signing secret is absent, the workflow deliberately falls back to a clearly named `-debug.apk` rather than pretending the artifact is production-signed.

Keep the release keystore and passwords in an offline backup. Losing the signing key prevents Android from accepting future in-place updates to installations signed with that key.

## Pinned release certificate

The long-term SIM Hub Android release key currently uses:

- Alias: `simhub-release`
- RSA 4096 / SHA256withRSA
- Certificate SHA-256: `9cac47bd005bd526de8348b7ba9fd217c08f8af04589012ed653f5162cfde84a`
- Certificate validity: 2026-10-06 through 2054-02-21

The certificate fingerprint is public metadata and is intentionally pinned in the repository. Release automation must fail if a configured keystore produces an APK signed by a different certificate.
