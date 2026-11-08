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
from backup_tools import backup_lock, verify_database, write_checksum

REPO = "blackkcold/simhub"
API = "https://api.github.com/repos/" + REPO + "/releases/latest"
DEV_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=20&page="
DEV_PAGE_SIZE = 20
DEV_MAX_PAGES = 5
REGISTRY = "ghcr.io/blackkcold/simhub-relay"
CERT_IDENTITY = "https://github.com/blackkcold/simhub/.github/workflows/release.yml@refs/heads/main"
DEV_CERT_IDENTITY = "https://github.com/blackkcold/simhub/.github/workflows/release-dev.yml@refs/heads/develop"
CERT_ISSUER = "https://token.actions.githubusercontent.com"
VERSION = re.compile(r"^v?([0-9]+)\.([0-9]+)\.([0-9]+)$")
DEV_VERSION = re.compile(r"^v?([0-9]+)\.([0-9]+)\.([0-9]+)-dev\.([1-9][0-9]*)$")
DIGEST_IMAGE = re.compile(r"^ghcr\.io/blackkcold/simhub-relay@sha256:[0-9a-f]{64}$")
LOCAL_IMAGE = re.compile(r"^simhub-relay:(?:local|rollback-[0-9]+|v[0-9]+\.[0-9]+\.[0-9]+)$")
ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".simhub-updater"
SOCKET = STATE_DIR / "control.sock"
STATUS = STATE_DIR / "status.json"
ENV_FILE = ROOT / ".env"


def release_channel():
    """Pinned to the deployment instance, never supplied by a web request."""
    try:
        values = [line.partition("=")[2].strip().strip("\'\\\"")
                  for line in ENV_FILE.read_text("utf-8").splitlines()
                  if line.startswith("SIMHUB_CHANNEL=")]
    except OSError:
        values = []
    channel = values[-1] if values else "stable"
    if channel not in ("stable", "dev"):
        raise ValueError("Invalid fixed deployment release channel")
    return channel


def host_port():
    try:
        lines = ENV_FILE.read_text("utf-8").splitlines()
    except OSError:
        lines = []
    ports = [line.partition("=")[2].strip().strip("\'\\\"")
             for line in lines if line.startswith("SIMHUB_HOST_PORT=")]
    value = ports[-1] if ports else "8787"
    if not value.isdecimal() or not 1 <= int(value) <= 65535:
        raise ValueError("Invalid private SIM Hub host port")
    return int(value)


def channel_version(value):
    return (DEV_VERSION if release_channel() == "dev" else VERSION).fullmatch(str(value))
AGENT_VERSION = "0.11.1"
RUN_LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
state = {}


def parse_version(value):
    match = VERSION.fullmatch(str(value))
    if match:
        return tuple(map(int, match.groups()))
    dev = DEV_VERSION.fullmatch(str(value))
    return tuple(map(int, dev.groups())) if dev else (0, 0, 0)


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
    validate_deployment()


def validate_deployment():
    """Validate the installed instance, including source-free deployment ZIPs.

    Compose and .env are controlled by the rootless host user, never by the
    bridge. The optional bundle manifest describes its initial release; updates
    may advance .env to a newer signed digest without rewriting that manifest.
    """
    channel = release_channel()
    manifest = ROOT / "deployment.json"
    if manifest.exists():
        data = json.loads(manifest.read_text("utf-8"))
        identity = DEV_CERT_IDENTITY if channel == "dev" else CERT_IDENTITY
        if (not isinstance(data, dict) or data.get("schemaVersion") != 1
                or data.get("channel") != channel
                or not channel_version(data.get("version", ""))
                or not DIGEST_IMAGE.fullmatch(str(data.get("image", "")))
                or data.get("cosignIdentity") != identity
                or data.get("cosignIssuer") != CERT_ISSUER):
            raise ValueError("Invalid or mismatched deployment bundle identity")
    # Do not require server/Dockerfile: verified deployments use a prebuilt
    # image and must never need a source checkout or a host-side Docker build.
    config = json.loads(compose("config", "--format", "json", timeout=15))
    service = config.get("services", {}).get("simhub", {}) if isinstance(config, dict) else {}
    image = service.get("image", "")
    if not (DIGEST_IMAGE.fullmatch(str(image)) or LOCAL_IMAGE.fullmatch(str(image))):
        raise ValueError("Untrusted configured SIM Hub image")
    environment = service.get("environment", {})
    if (not isinstance(environment, dict)
            or environment.get("SIMHUB_CHANNEL", "stable") != channel):
        raise ValueError("Compose service does not match the fixed release channel")
    ports = service.get("ports", [])
    if not ports or any(
            not isinstance(port, dict)
            or str(port.get("target")) != "8787"
            or str(port.get("published")) != str(host_port())
            or port.get("host_ip") not in {"127.0.0.1", "::1"}
            or port.get("protocol", "tcp") != "tcp" for port in ports):
        raise ValueError("SIM Hub must publish only its configured loopback port")
    if service.get("privileged") or service.get("read_only") is not True:
        raise ValueError("SIM Hub must remain an unprivileged read-only container")


def fetch_json(url, limit=131072):
    with urllib.request.urlopen(urllib.request.Request(
            url, headers={"Accept": "application/vnd.github+json",
                          "User-Agent": "simhub-rootless-updater/" + AGENT_VERSION}), timeout=15) as response:
        raw = response.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("Release metadata too large")
    return json.loads(raw)


def latest():
    channel = release_channel()
    if channel == "stable":
        doc = fetch_json(API)
        candidates = [doc]
    else:
        documents = []
        for page_number in range(1, DEV_MAX_PAGES + 1):
            page = fetch_json(DEV_API + str(page_number), 1048576)
            if not isinstance(page, list) or len(page) > DEV_PAGE_SIZE:
                raise ValueError("Invalid Dev release listing")
            documents.extend(page)
            if len(page) < DEV_PAGE_SIZE:
                break
        candidates = [entry for entry in documents
                      if isinstance(entry, dict) and
                      DEV_VERSION.fullmatch(str(entry.get("tag_name", ""))) and
                      entry.get("prerelease") is True and
                      entry.get("draft") is False and entry.get("published_at")]
        candidates.sort(key=lambda entry: parse_version(entry["tag_name"]), reverse=True)
        if not candidates:
            raise ValueError("No published Dev prerelease")
    doc = candidates[0]
    tag = doc.get("tag_name", "")
    if (not channel_version(tag) or doc.get("draft") or
            bool(doc.get("prerelease")) != (channel == "dev") or
            not doc.get("published_at")):
        raise ValueError("No published release in pinned channel")
    prefix = "https://github.com/" + REPO + "/releases/download/" + tag + "/"
    assets = {a["name"]: a["browser_download_url"] for a in doc.get("assets", [])
              if isinstance(a, dict) and isinstance(a.get("name"), str) and
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
    if (meta.get("schemaVersion") != 1 or meta.get("channel") != release_channel()
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
                if channel_version(v):
                    return v.removeprefix("v")
    except OSError:
        pass
    # Existing installations retain the legacy image tag until their first update.
    try:
        for line in ENV_FILE.read_text("utf-8").splitlines():
            if line.startswith("SIMHUB_IMAGE_TAG="):
                v = line.partition("=")[2].strip().strip("'\"")
                if channel_version(v):
                    return v.removeprefix("v")
    except OSError:
        pass
    try:
        with urllib.request.urlopen("http://127.0.0.1:" + str(host_port()) + "/readyz", timeout=2) as response:
            version = json.load(response).get("version")
        if channel_version(str(version)):
            return str(version).removeprefix("v")
    except (OSError, ValueError):
        pass
    try:
        text = (ROOT / "server" / "simhub_server.py").read_text("utf-8")
    except OSError:
        # A source-free deployment can be offline before its first start. Keep
        # status/check available; never invent an installed bundle version.
        return "0.0.0"
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
    if not channel_version(version):
        raise ValueError("Invalid deployed version")
    text = ENV_FILE.read_text("utf-8").splitlines(keepends=True)
    text = [line for line in text if not line.startswith((
        "SIMHUB_IMAGE_REF=", "SIMHUB_INSTALLED_VERSION="))]
    # Preserve all unrelated values, but never concatenate an added key
    # onto a final line whose source .env omitted its trailing newline.
    if text and not text[-1].endswith(("\n","\r")):
        text[-1] += "\n"
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
    save("backup", backupVerified=False)
    backups = STATE_DIR / "backups"
    backups.mkdir(mode=0o700, exist_ok=True)
    os.chmod(backups, 0o700)
    with backup_lock(ROOT):
        descriptor, filename = tempfile.mkstemp(dir=backups, prefix="simhub-" + str(time.time_ns()) + "-", suffix=".db")
        os.close(descriptor)
        target = Path(filename)
        remote = "/data/" + target.name
        # Exclusive owner-only snapshot; SQLite online backup includes WAL writes.
        code = ("import os,sqlite3;"
                "s=sqlite3.connect('file:/data/simhub.db?mode=ro',uri=True);"
                "os.close(os.open(" + repr(remote) + ",os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600));"
                "d=sqlite3.connect(" + repr(remote) + ");s.backup(d);d.close();s.close()")
        try:
            compose("exec", "-T", "simhub", "python3", "-c", code)
            compose("cp", "simhub:" + remote, str(target))
            os.chmod(target, 0o600)
            verified = verify_database(target)
            checksum=write_checksum(target).read_text("utf-8").split()[0]
            save("backup", backupVerified=True, backupSchemaVersion=verified["schemaVersion"],
                 lastBackup={"createdAt":int(time.time()),"sha256":checksum,
                             "schemaVersion":verified["schemaVersion"],"bytes":target.stat().st_size,
                             "integrityChecked":True,"restoreChecked":False})
            return str(target)
        except BaseException:
            target.unlink(missing_ok=True)
            target.with_name(target.name + ".sha256").unlink(missing_ok=True)
            raise
        finally:
            compose("exec", "-T", "simhub", "rm", "-f", remote)


def verify_image(image):
    if not DIGEST_IMAGE.fullmatch(image):
        raise ValueError("Untrusted image reference")
    # Strict keyless Sigstore identity pinning; SHA-256 from the unsigned release
    # manifest alone is NOT sufficient. No permissive fallback is supported.
    identity = DEV_CERT_IDENTITY if release_channel() == "dev" else CERT_IDENTITY
    run("cosign", "verify", "--certificate-identity=" + identity,
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
    # Require successive successful DB-aware readiness probes; a one-off response
    # is insufficient evidence that the replacement process is stable.
    deadline = time.monotonic() + 90
    consecutive = 0
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:" + str(host_port()) + "/readyz", timeout=3) as r:
                payload = json.load(r)
            consecutive = (consecutive + 1) if (
                payload.get("ok") and payload.get("version") == version
                and payload.get("channel", release_channel()) == release_channel()) else 0
            if consecutive >= 2:
                return True
        except (OSError, ValueError):
            consecutive = 0
        time.sleep(2)
    return False


def job(version):
    old_image = None
    old_version = None
    switched = False
    try:
        require_rootless()
        save("checking", requested=version, error=None, backupVerified=False, backupSchemaVersion=None)
        info = latest()
        if not channel_version(version) or version != info["version"] or parse_version(version) <= parse_version(current_version()):
            raise ValueError("Only a newer latest stable release may be deployed")
        image, schema = release_image(info)
        if schema != database_schema():
            raise ValueError("Schema migration requires supervised deployment")
        save("verifying_image", imageDigest=image.partition("@")[2], signatureVerified=False)
        pull_verified_image(image)
        save("verified_image", signatureVerified=True)
        old_version = current_version()
        old_id = current_image()
        old_image = "simhub-relay:rollback-" + str(int(time.time()))
        run("docker", "image", "tag", old_id, old_image)
        backup = backup_database()
        switch_start = time.monotonic()
        save("switching", previousTag=old_image, previousVersion=old_version,
             backup=backup, imageDigest=image.partition("@")[2])
        switched = True
        update_env(image, version)
        compose("up", "-d", "--no-build", "--pull", "never", "--force-recreate", "simhub")
        save("verifying")
        if not health(version):
            raise RuntimeError("Readiness/version check failed")
        save("complete", installed=version, switchElapsedMs=int((time.monotonic()-switch_start)*1000), error=None)
    except Exception as err:
        error = str(err)[:400]
        if switched and old_image and old_version:
            try:
                save("rolling_back", error=error, signatureVerified=False)
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
                if not isinstance(version, str) or not channel_version(version):
                    raise ValueError("Invalid version")
                if not RUN_LOCK.acquire(blocking=False):
                    raise ValueError("Update already running")
                try:
                    require_rootless()
                    save("queued", requested=version, error=None, signatureVerified=False)
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
            if state.get("phase") in {"queued", "checking", "verifying_image",
                                      "verified_image", "backup", "switching",
                                      "verifying", "rolling_back"}:
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
