# SIM Hub public exposure hardening (v0.3.1)

This guide is for the **publicly exposed administrator PWA** and the separately authenticated Android/Linux node API. It does not configure your DNS provider, reverse-proxy host, firewall, or third-party identity service automatically.

## Required deployment topology

- **Management origin:** `https://admin.simhub.example.com`. Put this hostname behind an identity-aware access gateway with phishing-resistant MFA (e.g. Cloudflare Access). The SIM Hub Admin Token, TOTP and Vault passphrase remain independent layers.
- **Node origin:** `https://node.simhub.example.com`. Keep this accessible over TLS without a browser challenge: Android/Modem nodes use device bearer tokens, not Access web cookies.
- **Relay:** only published at `127.0.0.1:8787` on the Docker host. Enable `SIMHUB_SEPARATE_SURFACES=true`, and set both `SIMHUB_MANAGEMENT_ORIGIN` and `SIMHUB_PUBLIC_BASE_URL` to distinct HTTPS origins. The server independently verifies the Host header and method/path allowlist.
- **Database:** Docker named volume at `/data`; read-only root filesystem with only the data volume and temporary directory writable.
- **Reverse proxy:** adapt `Caddyfile.example` for your real domains and certificate provider. Force HTTPS. Only proxy trusted hostnames. Keep `X-Forwarded-For` overwritten with the real client source, and set `SIMHUB_TRUSTED_PROXIES` to *only* the actual container/loopback proxy addresses.

### Migration order (do not break deployed nodes)

1. Make a consistent SQLite backup with `./scripts/backup.sh`. Protect the Vault recovery key separately offline.
2. Add the **node** hostname and TLS reverse proxy first; leave the old hostname temporarily reachable.
3. Point Modem Agents to the new node origin. Re-enroll Android agents on the new node origin where changing their relay URL directly is unsupported; test SMS receive and send.
4. Publish the management origin, activate your external access gateway and test logging in through it.
5. Set `SIMHUB_SEPARATE_SURFACES=true` and distinct origins; restart Relay. All existing browser sessions are expired by the v7 migration; use Admin Token + TOTP to log in again.
6. Check `/readyz` locally, then test node heartbeat, SMS inbound, outbound and reconnect. Remove previous public routes only after all nodes are moved.

**Important:** Enabling strict separation *before* repointing existing nodes will interrupt SMS synchronization. Never put a mandatory web login in front of node API requests.

## Security controls and expected behavior

| Control | Behavior |
|---|---|
| Session expiration | Absolute TTL of 8 h, idle TTL of 15 min by default; SSE and background polling do not extend idle lifetime |
| CSRF | Session-authenticated write requests require `X-SimHub-CSRF`; token returned by login/check and derived from server secret and session nonce |
| Origin and MIME | Incoming Origin must match configured origins; JSON writes require application/json |
| Privileged operations | SMS send, node enrollment, revocation, key rotation, event purge, and revoke-all require a recently elevated TOTP browser session (default 120 s) |
| Session revocation | The management panel can revoke all sessions; Admin Token rotation invalidates existing sessions on next request |
| Resource limits | Per-IP, per-session and per-device rate limits; 64 concurrent HTTP connections, 15 s socket timeout, and at most 3 SSE streams/session by default |
| Node SMS budget | Android: default 10 remote SMS sends per hour per Channel, persisted in private preferences. Modem: default 10 sends per rolling hour, persisted in SQLite; configurable by `SIMHUB_SMS_PER_HOUR` in Modem Agent environment |
| Privacy | Audit records and alert tooling should never include SMS plaintext, OTP, Node Key, Vault Key or raw enrollment packages |

The single-process, in-memory relay request limits are only a **defense-in-depth** layer. Configure edge DDoS protection, rate limits, TLS policy and WAF on your actual ingress as well. Do not enable untrusted forwarded-IP headers; the proxy must overwrite incoming values.

The browser Vault memory and the Relay session are separate concepts. Locking the Vault does not log out the administrator unless the explicit logout/forget action is used.

### Backup and recovery

Keep offline Vault recovery material independent of server database backups. Session/CSRF changes do not change message ciphertext format. If the admin token is exposed, rotate it and use the revoke-all function; if the controller/Vault is compromised, create a new Vault and rotate/re-enroll every node. The Relay cannot protect browser-side encryption keys against a fully malicious replacement of the PWA JavaScript; use a trusted controller device and protect software delivery.

### Acceptance checklist

- [ ] Admin hostname reachable through external access gateway and app TOTP.
- [ ] Node hostname does not serve the PWA, list devices or read encrypted event archives.
- [ ] Admin hostname rejects enrollment/Node heartbeat endpoints.
- [ ] Old node credentials continue working after moving to node hostname.
- [ ] Cross-origin session request and missing-CSRF write request return 403.
- [ ] A normal browser session cannot send SMS or enroll nodes before recent step-up.
- [ ] All sessions can be revoked and Admin Token rotation invalidates older sessions.
- [ ] Duplicate/replayed SMS commands cannot bypass device-local quotas.
- [ ] Reverse proxy never exposes port 8787, database volume, health internals or access tokens.
- [ ] GitHub CI server, surface-separation, modem and Android jobs pass.
