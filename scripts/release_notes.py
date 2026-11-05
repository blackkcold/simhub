#!/usr/bin/env python3
"""Generate release metadata from the matching CHANGELOG entry, never a copied template."""
from __future__ import annotations

import argparse
from pathlib import Path
import re


def section_for_version(changelog: str, version: str) -> tuple[str, str]:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Invalid semantic version")
    pattern = rf"(?m)^## v{re.escape(version)}(?:[ \t]+[^\n]*)?\n"
    match = re.search(pattern, changelog)
    if match is None:
        raise ValueError(f"CHANGELOG entry v{version} not found")
    next_header = re.search(r"(?m)^## v[0-9]+\.[0-9]+\.[0-9]+\b", changelog[match.end():])
    end = match.end() + next_header.start() if next_header else len(changelog)
    title = match.group(0).strip().removeprefix("## ").strip()
    body = changelog[match.end():end].strip()
    if not body or not re.search(r"(?m)^- ", body):
        raise ValueError("Release entry must include at least one concrete change")
    return title, body


def summary_for_version(changelog: str, version: str) -> str:
    title, _ = section_for_version(changelog, version)
    return title.split("—", 1)[-1].strip() if "—" in title else f"SIM Hub v{version}"


def render_notes(changelog: str, version: str) -> str:
    title, changes = section_for_version(changelog, version)
    heading = title.split("—", 1)[-1].strip() if "—" in title else title
    return (
        f"# SIM Hub v{version}\n\n"
        f"**{heading}**\n\n"
        f"## Changes\n\n{changes}\n\n"
        "## Verification and compatibility\n\n"
        "The signed APK and Relay OCI image are built and verified by the release workflow. "
        "Hardware-specific OEM background behavior, permissions, biometric prompts and "
        "foldable layouts still require physical-device acceptance testing.\n\n"
        "For detailed changes and upgrade instructions, see CHANGELOG.md and README.md.\n"
    )


def render_dev_notes(changelog: str, version: str) -> str:
    match = re.fullmatch(r"([0-9]+\.[0-9]+\.[0-9]+)-dev\.([1-9][0-9]*)", version)
    if not match:
        raise ValueError("Invalid Dev prerelease version")
    _, changes = section_for_version(changelog, match.group(1))
    return (
        f"# SIM Hub Dev v{version}\n\n"
        "Experimental development prerelease. Use isolated devices and data volumes; "
        "do not deploy over Stable.\n\n"
        f"## Changes\n\n{changes}\n\n"
        "## Verification and compatibility\n\n"
        "Android emulator checks do not establish physical SIM, OEM background or "
        "cross-network device behavior. P2P and Relay Lite remain experimental.\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--output")
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--dev", action="store_true")
    args = parser.parse_args()
    source = Path(args.changelog).read_text(encoding="utf-8")
    if args.dev and args.summary:
        parser.error("--dev cannot be combined with --summary")
    result = (render_dev_notes(source, args.version) if args.dev else
              summary_for_version(source, args.version) if args.summary else render_notes(source, args.version))
    if args.output:
        Path(args.output).write_text(result + ("" if result.endswith("\n") else "\n"), encoding="utf-8")
    else:
        print(result)


if __name__ == "__main__":
    main()
