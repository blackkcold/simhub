#!/usr/bin/env python3
"""Leak checks for the controlled packaging configuration.

Two modes:

* default (static) — the repository must not carry a generated description, a pin
  literal, or a non-reserved deployment domain in any tracked source, workflow or
  documentation file. Only the reserved example domains `node.relay.invalid` and
  `admin.relay.invalid` and public STUN hosts may appear.
* `--canary-absent <path> [...]` — every file under the given paths must be free of
  the canary value in `P2P_CANARY`. The packaging workflow uses this to prove a
  sensitive value did not reach an artifact, log, cache or report.

A truthful failure here is the intended outcome for a leak; the checks never delete
evidence to make themselves pass.
"""
import argparse
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESERVED_DOMAINS = {"node.relay.invalid", "admin.relay.invalid", "center-test.invalid",
                    "localhost", "127.0.0.1", "example.invalid"}
PIN_LITERAL = re.compile(r"sha256/[A-Za-z0-9+/]{43}=")
HOST_LITERAL = re.compile(r"https?://([A-Za-z0-9._-]+)")
SCANNED_SUFFIXES = {".java", ".kt", ".kts", ".py", ".yml", ".yaml", ".mjs", ".js", ".json", ".md", ".xml", ".sh"}
SKIP_PARTS = {".git", "build", ".gradle", "node_modules", "output", ".superpowers", "ui-snapshots"}
# Only the surfaces that can actually carry a deployment value.
HOST_SCAN_ROOTS = ("android/app/src/main", "android/app/build.gradle.kts", ".github/workflows")
ALLOWED_HOSTS = {
    "localhost", "127.0.0.1", "0.0.0.0",
    "stun.l.google.com", "stun.cloudflare.com",
    "schemas.android.com", "www.w3.org", "json-schema.org",
    "api.github.com", "github.com", "raw.githubusercontent.com", "shizuku.rikka.app",
    "token.actions.githubusercontent.com", "uploads.github.com", "objects.githubusercontent.com",
    "developer.android.com", "docs.github.com", "kotlinlang.org",
    # Public OSM map view, not a configured center domain or deployment endpoint.
    "www.openstreetmap.org",
    "maven.google.com", "repo.maven.apache.org", "dl.google.com",
    "services.gradle.org", "plugins.gradle.org", "repo1.maven.org",
}


def tracked_files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in SCANNED_SUFFIXES:
            continue
        if any(part in SKIP_PARTS for part in path.relative_to(ROOT).parts):
            continue
        yield path


def host_scan_scoped(relative):
    text = str(relative).replace("\\", "/")
    return any(text == root or text.startswith(root + "/") for root in HOST_SCAN_ROOTS)


def static_checks():
    problems = []
    for path in ROOT.rglob("center-config.json"):
        relative = path.relative_to(ROOT)
        if any(part in SKIP_PARTS for part in relative.parts):
            continue
        if "generated/centerConfig" not in str(path):
            problems.append(f"generated trust configuration committed: {relative}")
    for path in tracked_files():
        text = path.read_text(encoding="utf-8", errors="ignore")
        relative = path.relative_to(ROOT)
        if PIN_LITERAL.search(text) and "src/test/resources/center-test" not in str(relative) \
                and not str(relative).startswith("test_vectors"):
            problems.append(f"hard-coded SPKI pin literal: {relative}")
        if not host_scan_scoped(relative):
            continue
        for host in HOST_LITERAL.findall(text):
            host = host.lower()
            if host in ALLOWED_HOSTS or host in RESERVED_DOMAINS:
                continue
            if host.endswith((".invalid", ".example", ".test", ".local", ".localhost", ".example.com", ".example.org")):
                continue
            problems.append(f"non-reserved host literal {host!r} in {relative}")
    return problems


def canary_absent(paths, canary):
    problems = []
    needle = canary.encode()
    for target in paths:
        path = pathlib.Path(target)
        if not path.exists():
            continue
        files = [path] if path.is_file() else [item for item in path.rglob("*") if item.is_file()]
        for item in files:
            if "generated/centerConfig" in str(item):
                # The generated asset is the one place the value is allowed to exist.
                continue
            try:
                if needle in item.read_bytes():
                    problems.append(f"canary reached {item}")
            except OSError:
                continue
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--canary-absent", nargs="*", default=[])
    arguments = parser.parse_args()
    problems = static_checks()
    if arguments.canary_absent:
        canary = os.environ.get("P2P_CANARY", "")
        if not canary or len(canary) < 8:
            print("P2P_CANARY missing or too short", file=sys.stderr)
            return 2
        problems += canary_absent(arguments.canary_absent, canary)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print("center-config leak checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
