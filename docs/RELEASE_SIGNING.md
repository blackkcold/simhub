# Android release signing

SIM Hub never stores the signing private key or passwords in the public repository.

## Canonical signing identity

- Alias: `simhub-release`
- Algorithm: RSA 4096 / SHA256withRSA
- Certificate validity: 2026-10-06 through 2126-09-12
- Certificate SHA-256 fingerprint:
  `63:3F:60:0F:7E:C0:AC:5C:DC:DC:FF:B5:36:AD:E4:BE:EC:83:12:CC:08:34:87:A2:25:C8:C3:08:77:1F:D8:D3`

The fingerprint is public information and is pinned in the manual verification,
Stable release and Dev release workflows.

## Required GitHub Actions secrets

Repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore is decoded only into the ephemeral Actions runner. The private JKS is never uploaded as a repository or release artifact.

## Release policy

Normal releases start when a protected-branch PR changes the appropriate trigger:

| Channel | Target branch | Trigger | Workflow |
|---|---|---|---|
| Stable | `main` | `.github/release/v<VERSION>.trigger`, with `VERSION` on its first line | `.github/workflows/release.yml` |
| Dev | `develop` | `.github/dev-release/publish.trigger` | `.github/workflows/release-dev.yml` |

Stable requires the trigger version to match Android `versionName` and Relay
`APP_VERSION`. Dev derives its `-dev.N` version from the matching base version and
the release workflow sequence. Creating a tag manually does not start these
workflows; the publish job creates the tag after verification.

The workflows:

1. validate the trigger, version and source channel;
2. require successful CI, security scans and API 29/30 checks on the exact merged
   target-branch commit; Dev also requires the P2P preview and Android sandbox;
3. run regression checks and fail if a required signing secret is missing;
4. build the signed release APK and run `apksigner verify --print-certs`;
5. verify the pinned certificate and extract the actual APK package/version
   metadata; Stable also rejects native WebRTC artifacts;
6. upload the signed APK to a separate workflow artifact and sign/verify the
   immutable OCI image with the channel's workflow identity;
7. recheck the exact-commit gates, then create the tag and GitHub Release from a
   publish job that does not receive the keystore secrets.

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
