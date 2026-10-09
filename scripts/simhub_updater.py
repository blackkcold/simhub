#!/usr/bin/env python3
"""SIM Hub restricted, rootless-only OCI updater.

Only a prebuilt, digest-pinned, Sigstore-verified CI image may be deployed.
Never accepts an arbitrary URL, Docker/Compose command, filesystem path or shell.
Run under the *same non-root user* as a rootless Docker daemon.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socketserver
import subprocess
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

REPO = "blackkcold/simhub"
API = "https://api.github.com/repos/" + REPO + "/releases/latest"
REGISTRY = "ghcr.io/blackkcold/simhub-relay"
CERT_IDENTITY = "https://github.com/blackkcold/simhub/.github/workflows/release.yml@refs/heads/main"
CERT_ISSUER = "https://token.actions.githubusercontent.com"
VERSION = re.compile(r"^v?([0-9]+)\.([0-9]+)\.([0-9]+)$")
DIGEST_IMAGE = re.compile(r"^ghcr\.io/blackkcold/simhub-relay@sha256:[0-9a-f]{64}$")
LOCAL_IMAGE = re.compile(r"^simhub-relay:(?:local|rollback-[0-9]+|v[0-9]+\.[0-9]+\.[0-9]+)$")
ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".simhub-updater"
SOCKET = STATE_DIR / "control.sock"
STATUS = STATE_DIR / "status.json"
ENV_FILE = ROOT / ".env"
AGENT_VERSION = "0.11.0"
RUN_LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
state = {}


def parse_version(value):
    match = VERSION.fullmatch(str(value))
    return tuple(map(int, match.groups())) if match else (0, 0, 0)


def run(*argv, timeout=240):
    result = subprocess.run(argv, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=timeout,
                            env={**os.environ, "DOCKER_BUILDKIT": "1"})
    if result.returncode:
        raise RuntimeError("Command failed: " + argv[0] + " " + argv[1] + ": " + result.stdout[-600:])
    return result.stdout.strip()


def compose(*args, timeout=180):
    return run("docker", "compose", "-f", str(ROOT / "docker-compose.yml"),
               "--project-directory", str(ROOT), *args, timeout=timeout)


def require_rootless():
    if os.geteuid() == 0:
        raise PermissionError("Updater must never run as root")
    options = json.loads(run("docker", "info", "--format", "{{json .SecurityOptions}}", timeout=15))
    if not isinstance(options, list) or not any(str(x).split("=", 1)[-1] == "rootless"
                                                 for x in options):
        raise PermissionError("Docker daemon is not rootless; refusing deployment")
    if not (ROOT / "docker-compose.yml").is_file() or not ENV_FILE.is_file():
        raise ValueError("Missing initialized SIM Hub deployment")
    # The bridge may request an update, never modify the Compose deployment.
    if not (ROOT / "server" / "Dockerfile").is_file():
        raise ValueError("Missing SIM Hub deployment checkout")


def fetch_json(url, limit=131072):
    with urllib.request.urlopen(urllib.request.Request(
            url, headers={"Accept": "application/vnd.github+json",
                          "User-Agent": "simhub-rootless-updater/" + AGENT_VERSION}), timeout=15) as response:
        raw = response.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("Release metadata too large")
    return json.loads(raw)


def latest():
    doc = fetch_json(API)
    tag = doc.get("tag_name", "")
    if (not VERSION.fullmatch(tag) or doc.get("draft") or doc.get("prerelease")
            or not doc.get("published_at")):
        raise ValueError("No published stable release")
    prefix = "https://github.com/" + REPO + "/releases/download/" + tag + "/"
    assets = {a["name"]: a["browser_download_url"] for a in doc.get("assets", [])
              if isinstance(a.get("name"), str) and
              isinstance(a.get("browser_download_url"), str) and
              a["browser_download_url"].startswith(prefix)}
    return {"version": tag.removeprefix("v"), "tag": tag,
            "assets": assets, "publishedAt": doc["published_at"]}


def release_image(info):
    url = info["assets"].get("update-manifest.json")
    if not url:
        raise ValueError("Release has no update manifest")
    meta = fetch_json(url, 8192)
    server = meta.get("server")
    if (meta.get("schemaVersion") != 1 or meta.get("channel") != "stable"
            or meta.get("release") != info["version"] or not isinstance(server, dict)
            or not isinstance(server.get("dbSchema"), int)
            or not DIGEST_IMAGE.fullmatch(str(server.get("image", "")))):
        raise ValueError("No pinned trusted OCI image for this release")
    return server["image"], server["dbSchema"]


def current_version():
    try:
        for line in ENV_FILE.read_text("utf-8").splitlines():
            if line.startswith("SIMHUB_INSTALLED_VERSION="):
                v = line.partition("=")[2].strip()
                if VERSION.fullmatch(v):
                    return v.removeprefix("v")
    except OSError:
        pass
    # Existing installations retain the legacy image tag until their first update.
    try:
        for line in ENV_FILE.read_text("utf-8").splitlines():
            if line.startswith("SIMHUB_IMAGE_TAG="):
                v = line.partition("=")[2].strip().strip("'\"")
                if VERSION.fullmatch(v):
                    return v.removeprefix("v")
    except OSError:
        pass
    text = (ROOT / "server" / "simhub_server.py").read_text("utf-8")
    m = re.search(r'^APP_VERSION = "([0-9.]+)"', text, re.M)
    return m.group(1) if m else "0.0.0"


def save(phase, **updates):
    with STATE_LOCK:
        state.update(updates)
        state.update(phase=phase, updatedAt=int(time.time()))
        with tempfile.NamedTemporaryFile(mode="w", dir=STATE_DIR, prefix=".status-",
                                         delete=False, encoding="utf-8") as f:
            os.chmod(f.name, 0o600)
            json.dump(state, f)
            tmp = Path(f.name)
        tmp.replace(STATUS)


def update_env(image, version):
    if not (DIGEST_IMAGE.fullmatch(image) or LOCAL_IMAGE.fullmatch(image)):
        raise ValueError("Invalid deployment image")
    if not VERSION.fullmatch(version):
        raise ValueError("Invalid deployed version")
    text = ENV_FILE.read_text("utf-8").splitlines(keepends=True)
    text = [line for line in text if not line.startswith((
        "SIMHUB_IMAGE_REF=", "SIMHUB_INSTALLED_VERSION="))]
    text += ["SIMHUB_IMAGE_REF=" + image + "\n",
             "SIMHUB_INSTALLED_VERSION=" + version.removeprefix("v") + "\n"]
    with tempfile.NamedTemporaryFile(mode="w", dir=ROOT, prefix=".simhub-env-",
                                     delete=False, encoding="utf-8") as f:
        os.chmod(f.name, 0o600)
        f.writelines(text)
        tmp = Path(f.name)
    tmp.replace(ENV_FILE)


def current_image():
    cid = compose("ps", "-q", "simhub").strip()
    if not cid:
        raise RuntimeError("Relay container not running")
    return run("docker", "inspect", "-f", "{{.Image}}", cid)


def database_schema():
    return int(compose("exec", "-T", "simhub", "python3", "-c",
                       "import sqlite3; c=sqlite3.connect('/data/simhub.db');"
                       "print(c.execute('PRAGMA user_version').fetchone()[0]); c.close()"))


def backup_database():
    save("backup")
    backups = STATE_DIR / "backups"
    backups.mkdir(mode=0o700, exist_ok=True)
    stamp = str(time.time_ns())
    remote = "/data/simhub-updater-" + stamp + ".db"
    # sqlite3.backup gives a consistent snapshot even if WAL has uncheckpointed writes.
    code = ("import sqlite3; s=sqlite3.connect('/data/simhub.db');"
            "d=sqlite3.connect(" + repr(remote) + ");s.backup(d);d.close();s.close()")
    compose("exec", "-T", "simhub", "python3", "-c", code)
    target = backups / ("simhub-" + stamp + ".db")
    try:
        compose("cp", "simhub:" + remote, str(target))
        os.chmod(target, 0o600)
    finally:
        compose("exec", "-T", "simhub", "rm", "-f", remote)
    return str(target)


def verify_image(image):
    if not DIGEST_IMAGE.fullmatch(image):
        raise ValueError("Untrusted image reference")
    # Strict keyless Sigstore identity pinning; SHA-256 from the unsigned release
    # manifest alone is NOT sufficient. No permissive fallback is supported.
    run("cosign", "verify", "--certificate-identity=" + CERT_IDENTITY,
        "--certificate-oidc-issuer=" + CERT_ISSUER, image, timeout=120)


def pull_verified_image(image):
    verify_image(image)
    run("docker", "pull", image, timeout=360)
    ids = json.loads(run("docker", "image", "inspect", image,
                         "--format", "{{json .RepoDigests}}"))
    if image not in ids:
        raise RuntimeError("Pulled image digest does not match verified reference")
    # Close the verify/pull race: verify the exact digest again before deployment.
    verify_image(image)


def health(version):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8787/readyz", timeout=3) as r:
                payload = json.load(r)
            if payload.get("ok") and payload.get("version") == version:
                return True
        except (OSError, ValueError):
            pass
        time.sleep(2)
    return False


def job(version):
    old_image = None
    old_version = None
    switched = False
    try:
        require_rootless()
        save("checking", requested=version, error=None)
        info = latest()
        if version != info["version"] or parse_version(version) <= parse_version(current_version()):
            raise ValueError("Only a newer latest stable release may be deployed")
        image, schema = release_image(info)
        if schema != database_schema():
            raise ValueError("Schema migration requires supervised deployment")
        save("verifying_image", imageDigest=image.partition("@")[2])
        pull_verified_image(image)
        old_version = current_version()
        old_id = current_image()
        old_image = "simhub-relay:rollback-" + str(int(time.time()))
        run("docker", "image", "tag", old_id, old_image)
        backup = backup_database()
        save("switching", previousTag=old_image, previousVersion=old_version,
             backup=backup, imageDigest=image.partition("@")[2])
        switched = True
        update_env(image, version)
        compose("up", "-d", "--no-build", "--pull", "never", "--force-recreate", "simhub")
        save("verifying")
        if not health(version):
            raise RuntimeError("Readiness/version check failed")
        save("complete", installed=version, error=None)
    except Exception as err:
        error = str(err)[:400]
        if switched and old_image and old_version:
            try:
                save("rolling_back", error=error)
                update_env(old_image, old_version)
                compose("up", "-d", "--no-build", "--pull", "never",
                        "--force-recreate", "simhub")
                if not health(old_version):
                    raise RuntimeError("Previous version failed readiness")
                save("rolled_back", error=error, installed=old_version)
            except Exception as rollback_error:
                save("rollback_failed", error=error + "; rollback: " + str(rollback_error)[:200])
        else:
            save("failed", error=error)
    finally:
        RUN_LOCK.release()


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        try:
            raw = self.rfile.readline(8193)
            if len(raw) > 8192:
                raise ValueError("Request too large")
            request = json.loads(raw)
            if not isinstance(request, dict):
                raise ValueError("Expected request object")
            action = request.get("action")
            if action == "status":
                with STATE_LOCK:
                    snapshot = dict(state)
                result = {"ok": True, "agentVersion": AGENT_VERSION,
                          "mode": "rootless-verified", "installed": current_version(), **snapshot}
            elif action == "check":
                info = latest()
                result = {"ok": True, "latest": info["version"],
                          "installed": current_version(),
                          "available": parse_version(info["version"]) > parse_version(current_version())}
            elif action == "apply":
                version = request.get("version")
                if not isinstance(version, str) or not VERSION.fullmatch(version):
                    raise ValueError("Invalid version")
                if not RUN_LOCK.acquire(blocking=False):
                    raise ValueError("Update already running")
                try:
                    require_rootless()
                    save("queued", requested=version, error=None)
                    threading.Thread(target=job, args=(version,), daemon=True).start()
                except Exception:
                    RUN_LOCK.release()
                    raise
                result = {"ok": True, "phase": "queued", "requested": version}
            else:
                raise ValueError("Unsupported action")
        except Exception as err:
            result = {"ok": False, "error": str(err)[:400]}
        try:
            self.wfile.write(json.dumps(result).encode() + b"\n")
        except OSError:
            pass


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    allow_reuse_address = False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", action="store_true", required=True)
    args = parser.parse_args()
    require_rootless()
    # A rootless container maps UID 65534 to a subordinate host UID.
    # POSIX ACL grants access ONLY to this UID, never to all local users.
    mapped_uid = os.environ.get("SIMHUB_MAPPED_UID", "")
    if not mapped_uid.isdecimal() or int(mapped_uid) in (0, os.getuid()):
        raise ValueError("Missing/invalid mapped container UID; run install-updater.sh")
    STATE_DIR.mkdir(mode=0o700, exist_ok=True)
    os.chmod(STATE_DIR, 0o700)
    run("setfacl", "-m", "u:" + mapped_uid + ":--x", str(STATE_DIR), timeout=10)
    if STATUS.exists():
        try:
            old = json.loads(STATUS.read_text("utf-8"))
            if isinstance(old, dict):
                state.update(old)
            if state.get("phase") in {"queued", "checking", "verifying_image", "backup",
                                      "switching", "verifying", "rolling_back"}:
                # No blind restart rollback on a partially switched rootless daemon.
                save("interrupted", error="Updater restarted: inspect deployment and recover manually")
        except (OSError, ValueError):
            save("failed", error="Unreadable state")
    SOCKET.unlink(missing_ok=True)
    with Server(str(SOCKET), Handler) as server:
        os.chmod(SOCKET, 0o600)
        run("setfacl", "-m", "u:" + mapped_uid + ":rw", str(SOCKET), timeout=10)
        server.serve_forever()


if __name__ == "__main__":
    main()
