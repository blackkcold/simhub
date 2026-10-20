SIM Hub Dev prerelease trigger — 2026-10-20
Release source: develop after PR #110 (feat/dev-channel-branding).
Scope: channel-aware Dev orange launcher icon, native Compose branding, runtime-branded Web/PWA icon and manifest, pre-login DEV indicators and channel-specific UI accents; Stable appearance unchanged.
Verification: both Stable and Dev runtime routes, manifest/icon isolation and Android variant resource overlays; CI, P2P preview, security and CodeQL release gates must pass on the final develop SHA.
Experimental Dev release only; do not promote to Stable. Continue to require physical Android and external-network validation before Stable promotion.
