SIM Hub Dev prerelease trigger — 2026-10-19
Release source: develop after merged PR #105 (merge 3dccd8062bfaaf6ad38a29153d402a2d0790ddb7).
Scope: P0 peer-core protocol extraction; Android Peer pairing reliability; optional authenticated coturn/TURN ICE; security, release boundary, and data-channel status fixes.
Verified on PR head 90989db93cd312007bc101b914649690466bfe5a:
CI=success; P2P preview + 13 emulator tests=success; Security/CodeQL=success; Remote OTA=success.
Dev-only experimental, no Stable promotion. Real carrier/CGNAT deployments require separately provisioned trusted public signaling and coturn, and physical-device validation.
