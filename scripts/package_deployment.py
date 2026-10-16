#!/usr/bin/env python3
"""Build a deterministic, channel-bound, prebuilt-image deployment ZIP."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IMAGE = re.compile(r"^ghcr\.io/blackkcold/simhub-relay@sha256:[a-f0-9]{64}$")
STABLE = re.compile(r"^\d+\.\d+\.\d+$")
DEV = re.compile(r"^\d+\.\d+\.\d+-dev\.[1-9]\d*$")
FILES = (
    "docker-compose.yml",
    ".env.example",
    "scripts/deploy-verified.sh",
    "scripts/install-updater.sh",
    "scripts/simhub_updater.py",
    "LICENSE",
)


def build(version: str, channel: str, image: str, output: Path) -> None:
    if channel not in ("stable", "dev"):
        raise ValueError("Unknown deployment channel")
    if not (DEV if channel == "dev" else STABLE).fullmatch(version):
        raise ValueError("Incorrect channel version")
    if not IMAGE.fullmatch(image):
        raise ValueError("Image must use an immutable GHCR SHA256 digest")
    identity = (
        "https://github.com/blackkcold/simhub/.github/workflows/"
        + ("release-dev.yml@refs/heads/develop" if channel == "dev"
           else "release.yml@refs/heads/main")
    )
    manifest = {
        "schemaVersion": 1,
        "channel": channel,
        "version": version,
        "image": image,
        "cosignIdentity": identity,
        "cosignIssuer": "https://token.actions.githubusercontent.com",
    }
    readme = (
        "SIM Hub verified deployment - " + channel + " " + version + "\n\n"
        "1. Use a separate directory and rootless Docker daemon per channel.\n"
        "2. Copy .env.example to .env; set a unique Admin Token, TOTP secret,\n"
        "   public HTTPS origins, SIMHUB_CHANNEL=" + channel + ", COMPOSE_PROJECT_NAME=simhub-" + channel + "\n"
        "   and SIMHUB_HOST_PORT=" + ("8788" if channel == "dev" else "8787") + ".\n"
        "3. Configure HTTPS using the existing proxy (do not expose 8787/8788).\n"
        "4. Run bash scripts/deploy-verified.sh; it checks the signed digest\n"
        "   before switching the instance and never builds server code locally.\n"
        "5. After confirming /readyz, use bash scripts/install-updater.sh from\n"
        "   this same directory to configure the channel-specific rootless updater.\n"
        "Never use a Stable data volume, Vault, SMS secrets or paired devices for Dev.\n"
        "For an existing deployment back up the volume and .env before upgrading.\n"
        "Real public P2P/NAT reliability remains subject to on-device validation.\n"
    )
    entries = {p: (ROOT / p).read_bytes() for p in FILES}
    entries["deployment.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    entries["DEPLOY-README.txt"] = readme.encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            archive.writestr(entry, data)
    print(f"PACKAGED channel={channel} version={version} sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--channel", required=True, choices=("stable", "dev"))
    p.add_argument("--version", required=True)
    p.add_argument("--image", required=True)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    build(args.version, args.channel, args.image, args.output)
