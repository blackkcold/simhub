# Build and release verification

Release publication waits for CI, Security scan, all six existing CodeQL jobs,
and Android API 29 and 30 compatibility checks on the exact release commit. The
native CodeQL comparison and existing branch-rule contexts remain unchanged.
Each analysis also checks its SARIF locally: security severity 7.0 or higher,
error-level findings, missing reports, and unsuccessful analysis block release.
No baseline or unchanged-result exemption hides current high-severity findings.

PR and push analyses both use full repository scope. The workflow sets
`CODEQL_ACTION_DIFF_INFORMED_QUERIES=false` before initialization, so the local
SARIF gate also sees findings outside the PR's changed lines. This environment
override is supported by the pinned Action v4.39.3
[implementation](https://github.com/github/codeql-action/blob/33d7b85da1a04171e211befe2c739303b9a4c066/src/feature-flags.ts);
it is not a documented stable Action input and must be rechecked when updating
that pinned SHA.

`python3 scripts/run_regressions.py` discovers every `test_*.py` under `server`,
`modem_agent`, and `scripts`, plus `web/test_*.mjs`. Each Python module runs in a
fresh process so fixture environment variables do not leak between suites.
Unittest discovery works without a `__main__` block; standalone documentation
and icon checks retain their script entry points. The Playwright browser suite
runs separately after browser installation, or with `--include-ui` locally.
Android CI runs full debug lint, unit/build checks, API29/30 recovery tests, and
the existing API35 isolated Relay acceptance suite. Emulator tests do not prove
physical carrier delivery or OEM background behavior.

Dev's `P2P isolated preview`, `preview` and `android-sandbox` results are also
required on the exact release commit. Scripts marked `p2p-integration` run in
that provisioned workflow because they need real coturn or Gradle packaging;
ordinary discovery reports their dedicated gate instead of silently skipping
them. Use `--group p2p-integration` only after provisioning those dependencies.

## Pinned inputs

- Every reusable workflow action is pinned to an upstream commit SHA. Mutable
  action tags were resolved through the official GitHub repository refs on
  2026-11-06, including annotated-tag dereferencing.
- The Python 3.13 Alpine and Caddy 2 multi-platform base-image manifests are
  digest-pinned. Their SHA-256 values were verified against the exact manifest
  bytes returned by Docker's official registry. Both contain linux/amd64 and
  linux/arm64 images.
- Python direct constraints remain in each component's `requirements.in`.
  `requirements.txt` pins the resolved transitive versions and enables pip hash
  verification with the non-yanked wheel/source hashes from the official PyPI
  release JSON. CI, the Relay container and modem installation all consume the
  same verified pins. Hashes establish artifact identity, not absence of bugs.
- OCI releases request BuildKit provenance and SBOM attestations in addition to
  the existing Cosign signature bound to the specific release workflow identity.
  The SBOM is attached to the published OCI image; it is not a separate APK SBOM.

Refresh dependencies deliberately in a PR, inspect upstream releases and security
advisories, regenerate hashes (for example with `pip-compile --generate-hashes`
from each `requirements.in`), and run complete CI plus both image architectures.
Review any Python version selected by both components together. Update pinned
image digests and action SHAs in the same controlled process when relevant.
Pinned inputs improve reproducibility; GitHub-hosted runner images, Android SDK
downloads and hardware behavior still require their independent checks.

## Runtime capacity

Compose defaults to 512 MiB memory, 2 CPUs and 128 PIDs. These are configurable
via `SIMHUB_MEMORY_LIMIT`, `SIMHUB_CPU_LIMIT` and `SIMHUB_PIDS_LIMIT`; they are not
measured capacity guarantees. The default password-verification budget is two
concurrent Argon2 jobs of 64 MiB each, separate from the HTTP connection budget.
Increase memory proportionally before raising `SIMHUB_MAX_KDF_CONCURRENCY`, and
measure representative event/command traffic and database size before setting
limits for a larger deployment.
