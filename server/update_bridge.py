"""Narrow, authenticated update transport and immutable release discovery.

No Docker socket is ever mounted into the relay. The privileged host updater
accepts only fixed actions over a local Unix socket, and verifies release assets.
"""
from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
import urllib.request
from pathlib import Path

REPO = "blackkcold/simhub"
API = "https://api.github.com/repos/" + REPO + "/releases/latest"
DEV_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=100"
CHANNEL = os.environ.get("SIMHUB_CHANNEL", "stable").strip()
if CHANNEL not in ("stable", "dev"):
    raise ValueError("Unknown release channel")
SOCKET = os.environ.get("SIMHUB_UPDATER_SOCKET", "/run/simhub-updater/control.sock")
IGNORE_FILE = Path(os.environ.get("SIMHUB_UPDATE_IGNORE_FILE", "/data/update-ignore.json"))
VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
DEV_VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)-dev\.([1-9][0-9]*)$")
CHANNEL_VERSION = DEV_VERSION if CHANNEL == "dev" else VERSION
_lock = threading.Lock()
_cache = (0., None)


def version_tuple(value):
    match = CHANNEL_VERSION.fullmatch(str(value))
    return tuple(int(x) for x in match.groups()) if match else (0, 0, 0)


def release(force: bool = False):
    """Discover the latest stable release; explicit UI checks bypass the 15-minute cache.

    A five-second coalescing window prevents repeated manual clicks from
    creating parallel GitHub API requests. Failed checks never replace the
    last successful cache or silently report an outdated version as current.
    """
    global _cache
    with _lock:
        age = time.monotonic() - _cache[0]
        if _cache[1] is not None and age < (5 if force else 900):
            return {**_cache[1], "fromCache": True, "cacheAgeSeconds": max(0, int(age))}
        request = urllib.request.Request(DEV_API if CHANNEL == "dev" else API, headers={
            "Accept": "application/vnd.github+json", "User-Agent": "simhub-updater/0.16"
        })
        with urllib.request.urlopen(request, timeout=8) as response:
            raw = response.read(1048577 if CHANNEL == "dev" else 262145)
        if len(raw) > (1048576 if CHANNEL == "dev" else 262144):
            raise ValueError("Release metadata exceeds size limit")
        document = json.loads(raw)
        if CHANNEL == "dev":
            if not isinstance(document, list):
                raise ValueError("Invalid Dev release feed")
            matches = [entry for entry in document if isinstance(entry, dict)
                       and DEV_VERSION.fullmatch(str(entry.get("tag_name", "")))
                       and entry.get("draft") is False
                       and entry.get("prerelease") is True
                       and entry.get("published_at")]
            matches.sort(key=lambda entry: version_tuple(entry["tag_name"]), reverse=True)
            if not matches:
                raise ValueError("No verified Dev prerelease")
            result = matches[0]
        else:
            result = document
        if not isinstance(result, dict):
            raise ValueError("Invalid release metadata")
        tag = result.get("tag_name", "")
        if (not isinstance(tag, str) or not CHANNEL_VERSION.fullmatch(tag)
                or result.get("draft") or bool(result.get("prerelease")) != (CHANNEL == "dev")
                or not result.get("published_at")):
            raise ValueError("Untrusted release channel or version")
        prefix = "https://github.com/blackkcold/simhub/releases/download/" + tag + "/"
        assets = {item["name"]: item["browser_download_url"]
                  for item in result.get("assets", [])
                  if isinstance(item, dict) and isinstance(item.get("name"), str)
                  and isinstance(item.get("browser_download_url"), str)
                  and item["browser_download_url"].startswith(prefix)}
        data = {"version": tag.removeprefix("v"), "tag": tag, "channel": CHANNEL, "assets": assets,
                "publishedAt": result["published_at"], "checkedAt": int(time.time())}
        _cache = (time.monotonic(), data)
        return {**data, "fromCache": False, "cacheAgeSeconds": 0}

def manifest():
    info = release()
    url = info["assets"].get("update-manifest.json")
    if not url:
        return None
    with urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "simhub-updater/0.9.1"
    }), timeout=10) as response:
        raw = response.read(8193)
    if len(raw) > 8192:
        raise ValueError("Update manifest exceeds size limit")
    data = json.loads(raw)
    if (data.get("schemaVersion") != 1 or data.get("release") != info["version"]
            or data.get("channel") != CHANNEL):
        raise ValueError("Manifest/release mismatch")
    return data


def android_ota():
    data = manifest()
    if not data:
        return {"available": False}
    android = data.get("android", {})
    info = release()
    name = android.get("asset", "")
    checksum = android.get("sha256", "")
    if (not re.fullmatch(r"simhub-agent-v[0-9.]+(?:-dev\.[1-9][0-9]*)?-release\.apk", name)
            or not re.fullmatch(r"[0-9a-f]{64}", checksum)
            or android.get("packageName") != ("com.blackkcold.simhub.dev" if CHANNEL == "dev" else "com.blackkcold.simhub")
            or not isinstance(android.get("versionCode"), int)
            or android["versionCode"] <= 0 or name not in info["assets"]):
        raise ValueError("Invalid APK metadata")
    return {"available": True, "versionName": data["release"],
            "versionCode": android["versionCode"],
            "url": info["assets"][name], "sha256": checksum,
            "notes": data.get("notes", "")[:1000]}


def ignored():
    try:
        v = json.loads(IGNORE_FILE.read_text("utf-8"))
        return v.get("version") if CHANNEL_VERSION.fullmatch(str(v.get("version", ""))) else None
    except (OSError, ValueError, AttributeError):
        return None


def set_ignored(version):
    if version is not None and not CHANNEL_VERSION.fullmatch(str(version)):
        raise ValueError("Invalid version")
    IGNORE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = IGNORE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps({"version": version}), encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(IGNORE_FILE)


def host(action, version=None):
    if action not in {"status", "check", "apply"}:
        raise ValueError("Unsupported updater action")
    if version is not None and not CHANNEL_VERSION.fullmatch(version):
        raise ValueError("Invalid target version")
    if action == "apply":
        status = host("status")
        if not status.get("ok") or status.get("mode") != "rootless-verified":
            raise PermissionError("Privileged/legacy updater is forbidden; install the rootless verified updater")
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
        conn.settimeout(12 if action == "check" else 3)
        conn.connect(SOCKET)
        request = json.dumps({"action": action, "version": version}).encode() + b"\n"
        conn.sendall(request)
        chunks = bytearray()
        while b"\n" not in chunks:
            block = conn.recv(4096)
            if not block:
                raise OSError("Updater closed connection")
            chunks.extend(block)
            if len(chunks) > 32768:
                raise ValueError("Updater response too large")
        result = json.loads(chunks.split(b"\n", 1)[0])
        if not isinstance(result, dict):
            raise ValueError("Updater response invalid")
        return result
