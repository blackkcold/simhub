# SIM Hub release channels: Stable and Dev

SIM Hub distributes exactly two tracks. **Stable is the default**, uses the existing
signed APK `com.blackkcold.simhub`, and must never include experimental P2P.
**Dev** is a GitHub pre-release with the separately installed
`com.blackkcold.simhub.dev` app. Dev may contain P2P and other experimental
code, and is not intended to replace a production SIM node.

| Property | Stable | Dev |
|---|---|---|
| Git branch | `main` | `develop` |
| Git tag | `vX.Y.Z` | `vX.Y.Z-dev.N` |
| GitHub Release | published, not prerelease | published with `prerelease=true` |
| Android package | `com.blackkcold.simhub` | `com.blackkcold.simhub.dev` |
| Android build | `assembleRelease` | `assembleDevRelease -PsimhubDevSequence=N` |
| Relay image | `ghcr.io/blackkcold/simhub-relay:vX.Y.Z` | `...:vX.Y.Z-dev.N` |
| Updater metadata | GitHub `releases/latest` | GitHub releases list, Dev tags only |
| Cosign workflow identity | `release.yml@refs/heads/main` | `release-dev.yml@refs/heads/develop` |
| Release channel | `stable` | `dev` |
| Default loopback port | 8787 | 8788 |
| Rootless updater systemd unit | `simhub-updater.service` | `simhub-updater-dev.service` |

## Publishing and promotion

1. Stable is built **only from `main`** by the existing signed release workflow
   using a changed `.github/release/vX.Y.Z.trigger`. It publishes the existing
   normal GitHub release. The stable update source is unchanged.
2. Dev code lives on `develop`, with PRs passing CI and CodeQL. To request a Dev
   release, change `.github/dev-release/publish.trigger` **on `develop`**;
   this triggers `.github/workflows/release-dev.yml`. GitHub Actions' own
   monotonically increasing run number determines the `-dev.N` suffix and
   distinct Android `versionCode`.
3. Dev publishing builds a signed Android APK, multiarchitecture GHCR image,
   Sigstore/Cosign OCI signature, verified deployment ZIP, immutable source/docs archives, checksums and
   `update-manifest.json`, then publishes a GitHub **Pre-release**.
4. Experimental feature PRs such as P2P target `develop`, not `main`.
   Promoting a feature to Stable requires explicit review, physical-device
   acceptance, new release tests and a deliberate PR; never merge the entire
   experimental `develop` branch into `main` automatically.
5. Before either release workflow creates an immutable tag, its publish job
   **independently waits** for push-triggered `CI` and `Security scan`, plus
   all six CodeQL analysis/compatibility jobs, to report `success` on the
   exact release SHA. Missing or failed jobs block publication. GitHub's
   separate native `CodeQL` PR aggregate compares against a PR base branch;
   it is not a release-commit scan and may show `neutral` when a Stable-to-Dev
   sync PR compares intentionally different P2P scan configurations. That
   cross-branch summary never replaces or weakens the six real scans.
6. A release fails rather than accepting a missing signing secret, wrong APK
   certificate, mutable OCI tag, wrong Cosign workflow, mismatched manifest,
   downgrade or wrong channel.

**Important:** Dev APK and Stable APK currently use the same repository-controlled
release signing certificate but **different Android package IDs** and update
metadata. Neither can be installed as an update to the other package. Separate
per-channel signing keys can be introduced only with an explicitly coordinated
long-term Dev certificate; generating a new key per build would break Dev OTA.

## Downloadable server packages

Every Stable Release and Dev Pre-release contains a version-matched
`simhub-server-vX.Y.Z[-dev.N]-deploy.zip`, in addition to the signed Android APK,
GHCR linux/amd64 and linux/arm64 images, source/docs bundles and integrity
checks. The server ZIP includes `deployment.json`, a digest-pinned image,
the exact channel-specific Cosign identity, Compose and rootless updater scripts,
and `scripts/deploy-verified.sh`. It has **no operator secrets, SMS, or Vault data**.
Copy `.env.example` to `.env`, configure independent HTTPS origins and secrets,
then run `bash scripts/deploy-verified.sh` (without sudo). The script refuses
wrong channel/port/project, rootful Docker and untrusted OCI images.
The update manifest retains its existing source asset and adds
`server.deploymentAsset` and `server.deploymentSha256` without changing its schema.

## Safe dual deployment on one rootless Docker host

Use **two different working directories** and independent `.env` files.
Never share a Docker Compose project, named volume, SQLite DB, Global Vault,
node enrolment identity, Admin Token, TOTP secret or updater control socket.

Stable instance, e.g. `/srv/simhub-stable`:

```dotenv
SIMHUB_CHANNEL=stable
COMPOSE_PROJECT_NAME=simhub-stable
SIMHUB_HOST_PORT=8787
```

Dev instance, e.g. `/srv/simhub-dev`:

```dotenv
SIMHUB_CHANNEL=dev
COMPOSE_PROJECT_NAME=simhub-dev
SIMHUB_HOST_PORT=8788
```

Create the two configurations independently, for example:

```bash
# In the Stable checkout
python3 scripts/setup.py --channel stable --admin-domain admin.example.com --node-domain node.example.com

# In a separate Dev checkout; use a separate external HTTPS reverse proxy
python3 scripts/setup.py --channel dev --mode external --admin-domain dev-admin.example.com --node-domain dev-node.example.com
```

The Dev HTTPS proxy must route to `127.0.0.1:8788` and Stable to
`127.0.0.1:8787`. Both listen only on loopback. When using an existing
managed Caddy service, **do not start a second Caddy on the same 80/443 ports**;
use `--mode external` for Dev. Before starting, explicitly review the generated
`.env` and confirm `COMPOSE_PROJECT_NAME` is different for each deployment.

Once each instance has been deployed with rootless Docker, run
`bash scripts/install-updater.sh` from that instance's own checkout.
The installer creates a distinct systemd user unit for each channel and checks
its private Unix control socket. It must never be run as root.

## Channel-pinned upgrades and rollback

- A Stable client **never** discovers or installs a Dev tag; Dev discovers
  published non-draft `-dev.N` pre-releases only.
- Android verifies exact metadata, version, SHA-256, package name and installed
  APK signing identity before requesting installation.
- The host Rootless Updater verifies the **exact channel-specific Cosign
  workflow identity** and immutable image digest before switching containers.
- Backups and rollback are per checkout / Docker project. An incompatible
  SQLite schema prevents unattended migration; use a supervised migration and
  tested offline restore.
- Cross-channel switching is **not** an OTA: provision a second independent
  environment and enrol/test separately. Android users may install the Dev
  package alongside Stable, but only one default SMS handler may be active.

Dev UI and diagnostics are labeled; Dev releases can be unreliable under
carrier NAT, Doze and OEM battery restrictions. CI is not physical-device P2P
acceptance. Production credentials, production SMS and personal OTP must not
be copied into the Dev environment.
