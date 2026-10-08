# SIM Hub — guided deployment

## Recommended first install (Linux VPS)

Ensure Docker Engine, Docker Compose plugin and DNS A records exist. Open inbound
TCP 80 and 443. Do not expose TCP 8787 directly to the internet.

```sh
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
```

The installer checks Docker, DNS records against the detected public IPv4 (when
available), ports, and safe hostname separation. It generates a strong emergency
administrator token and TOTP secret in an owner-only `.env`; does not copy them
to GitHub or send them to third parties. Save them securely and import the TOTP
secret into your authenticator app. After TLS issuance, use the management domain
to log in and register Passkeys from **Settings → Security**.

A successful installer run does not prove external reachability. Verify both
HTTPS hosts and certificate chain from a separate device/network. DNS automation
through third-party registrars is intentionally not attempted by this script.

## Deploy behind an existing reverse proxy

```sh
python3 scripts/setup.py --mode external \
  --admin-domain admin.example.com --node-domain node.example.com
```

Configure your own reverse proxy using `Caddyfile.example`. Ensure HTTPS on both
hostnames, overwrite `X-Forwarded-For` with an authenticated client IP, and point
to `127.0.0.1:8787`. Management and node routes must remain separated.

If DNS has not propagated use `--skip-dns-check --no-start` to write configuration
then rerun with `--upgrade` (and the chosen `--mode`) once records work.

## Upgrade

```sh
git pull --ff-only
python3 scripts/setup.py --upgrade --admin-domain admin.example.com \
  --node-domain node.example.com
```

This preserves existing `.env`, Caddyfile, and Docker volumes. Backup using
`./scripts/backup.sh` beforehand. **Do not** use `--upgrade` to rename a
management domain: WebAuthn credentials bind to that hostname and require a
deliberate migration and recovery process.

## Post-install

1. Resolve both hostnames to the VPS; verify public TLS and redirects.
2. Log in using the bootstrap administrator token + TOTP.
3. Create or import your local Vault (never upload Vault Key to the relay).
4. Register at least two Passkeys, test login and step-up, and store recovery
   materials offline.
5. Enroll Android or modem nodes using the **node** domain.
6. Test SMS reception and outgoing SMS, then configure encrypted-data backup.
7. For diagnostics, use internal `/readyz` inside the relay container and
   `docker compose logs --tail=50 simhub`.

Troubleshooting: `docker compose -f docker-compose.yml -f compose.caddy.yml logs --tail=50 caddy`
for TLS failures; check A/AAAA records, inbound TCP 80/443 and whether another
process already uses those ports. Remove stale AAAA records when IPv6 is not
routable. A host-only health probe works when started with the supplied Compose
network and `SIMHUB_TRUST_DOCKER_GATEWAY=true`; do not widen trusted CIDRs.
