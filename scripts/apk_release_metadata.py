#!/usr/bin/env python3
"""Bind release metadata to the actual signed APK inspected by Android aapt."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PACKAGES = {"stable": "com.blackkcold.simhub", "dev": "com.blackkcold.simhub.dev"}
VERSIONS = {"stable": re.compile(r"[0-9]+\.[0-9]+\.[0-9]+"),
            "dev": re.compile(r"[0-9]+\.[0-9]+\.[0-9]+-dev\.[1-9][0-9]*")}
PACKAGE_LINE = re.compile(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'(?:\s|$)", re.M)


def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_badging(badging: str, apk: Path, channel: str, version: str) -> dict:
    match = PACKAGE_LINE.search(badging)
    if not match:
        raise ValueError("APK package metadata unavailable")
    package, code, name = match.groups()
    data = {"packageName": package, "versionCode": int(code), "versionName": name,
            "sha256": checksum(apk)}
    validate(data, apk, channel, version)
    return data


def validate(data: dict, apk: Path, channel: str, version: str) -> int:
    if (channel not in PACKAGES or not VERSIONS[channel].fullmatch(version)
            or not isinstance(data, dict) or data.get("packageName") != PACKAGES[channel]
            or data.get("versionName") != version
            or type(data.get("versionCode")) is not int
            or not 0 < data["versionCode"] <= 2100000000
            or data.get("sha256") != checksum(apk)):
        raise ValueError("APK metadata, channel, version or digest mismatch")
    return data["versionCode"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("inspect", "verify"))
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--channel", choices=tuple(PACKAGES), required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--aapt")
    args = parser.parse_args()
    if args.operation == "inspect":
        if not args.aapt:
            parser.error("inspect requires --aapt")
        badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
        data = inspect_badging(badging, args.apk, args.channel, args.version)
        args.metadata.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")
    else:
        data = json.loads(args.metadata.read_text(encoding="utf-8"))
        validate(data, args.apk, args.channel, args.version)
    print(data["versionCode"])


if __name__ == "__main__":
    main()
