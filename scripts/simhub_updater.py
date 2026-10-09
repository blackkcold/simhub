#!/usr/bin/env python3
"""Host-only SIM Hub updater. Never run inside the web/relay container.

Fixed GitHub repository, fixed Docker service and fixed local Compose project.
No user-controlled URL, file path, shell, arbitrary image or Docker command.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import signal
import socket
import socketserver
import stat
import subprocess
import tempfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

REPO = "blackkcold/simhub"
API = "https://api.github.com/repos/" + REPO + "/releases/latest"
VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
AGENT_VERSION = "0.9.1"
MAX_SOURCE_SIZE = 30 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".simhub-updater"
SOCKET = STATE_DIR / "control.sock"
STATUS = STATE_DIR / "status.json"
ENV_FILE = ROOT / ".env"
RUN_LOCK = threading.Lock()
state = {}


def parse_version(text):
    match = VERSION.fullmatch(str(text))
    return tuple(map(int, match.groups())) if match else (0, 0, 0)


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "SIMHub-Updater/" + AGENT_VERSION,
        "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=15) as result:
        raw = result.read(128 * 1024 + 1)
    if len(raw) > 128 * 1024:
        raise ValueError("Release metadata is too large")
    return json.loads(raw)


def download(url, target, max_size):
    # Only trusted repository release-asset URLs; never accept URLs from clients.
    prefix = "https://github.com/" + REPO + "/releases/download/"
    if not url.startswith(prefix):
        raise ValueError("Untrusted download origin")
    req = urllib.request.Request(url, headers={"User-Agent": "SIMHub-Updater/" + AGENT_VERSION})
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(req, timeout=50) as response, open(target, "wb") as output:
        while chunk := response.read(128 * 1024):
            size += len(chunk)
            if size > max_size:
                raise ValueError("Asset size exceeds limit")
            digest.update(chunk)
            output.write(chunk)
    return digest.hexdigest()


def latest():
    info = fetch_json(API)
    tag = info.get("tag_name", "")
    if (not VERSION.fullmatch(tag) or info.get("prerelease") or info.get("draft")
        or not info.get("published_at")):
        raise ValueError("No trusted stable Release")
    prefix = "https://github.com/" + REPO + "/releases/download/" + tag + "/"
    assets = {x["name"]:x["browser_download_url"] for x in info.get("assets", [])
              if isinstance(x.get("name"),str) and x.get("browser_download_url","").startswith(prefix)}
    return {"version":tag.removeprefix("v"), "assets":assets, "tag":tag,
            "publishedAt":info["published_at"]}


def manifest(info, folder):
    url = info["assets"].get("update-manifest.json")
    if not url:
        raise ValueError("Release is missing update-manifest.json")
    filename = folder / "update-manifest.json"
    download(url, filename, 8192)
    payload = json.loads(filename.read_text("utf-8"))
    if (payload.get("schemaVersion") != 1 or payload.get("channel") != "stable"
        or payload.get("release") != info["version"]):
        raise ValueError("Manifest/release mismatch")
    return payload


def current_tag():
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text("utf-8").splitlines():
            if line.startswith("SIMHUB_IMAGE_TAG="):
                tag = line.partition("=")[2].strip().strip("'\"")
                if tag.startswith("v") and VERSION.fullmatch(tag):
                    return tag
    source = ROOT / "server" / "simhub_server.py"
    if source.exists():
        match = re.search(r'^APP_VERSION = "([0-9.]+)"',source.read_text("utf-8"),re.M)
        if match:
            return "v" + match.group(1)
    return "v0.0.0"


def save(phase, **values):
    global state
    state = {**state, **values, "phase":phase, "updatedAt":int(time.time())}
    target = STATUS.with_suffix(".tmp")
    target.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    os.chmod(target, 0o600)
    target.replace(STATUS)


def run(*args, timeout=300, extra_env=None):
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    p = subprocess.run(args, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError(f"Command failed ({args[0:3]}): {p.stdout[-1500:]}")
    return p.stdout


def compose(*args, timeout=180):
    return run("docker", "compose", *args, timeout=timeout)


def image_backup():
    container = compose("ps", "-q", "simhub").strip()
    if not container:
        raise RuntimeError("Relay container not running")
    image_id = run("docker", "inspect", "-f", "{{.Image}}", container).strip()
    return image_id


def update_env(image_tag):
    if not re.fullmatch(r"(?:v\d+\.\d+\.\d+|rollback-\d+)", image_tag):
        raise ValueError("Invalid image identifier")
    lines = ENV_FILE.read_text("utf-8").splitlines(keepends=True)
    lines = [line for line in lines if not line.startswith("SIMHUB_IMAGE_TAG=")]
    lines.append("SIMHUB_IMAGE_TAG=" + image_tag + "\n")
    temp = ENV_FILE.with_name(".env.simhub-update")
    temp.write_text("".join(lines), "utf-8")
    os.chmod(temp, 0o600)
    temp.replace(ENV_FILE)


def backup_database():
    save("backup")
    backup_dir = STATE_DIR / "backups"
    backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = str(int(time.time()))
    remote = "/data/simhub-updater-" + stamp + ".db"
    code = ("import sqlite3; source=sqlite3.connect('/data/simhub.db'); "
            "destination=sqlite3.connect(" + repr(remote) + "); "
            "source.backup(destination); destination.close(); source.close()")
    compose("exec", "-T", "simhub", "python3", "-c", code)
    target = backup_dir / ("simhub-" + stamp + ".db")
    try:
        compose("cp", "simhub:" + remote, str(target))
        os.chmod(target, 0o600)
    finally:
        compose("exec", "-T", "simhub", "rm", "-f", remote)
    return str(target)


def extract(archive, destination):
    with zipfile.ZipFile(archive) as package:
        files = package.infolist()
        if len(files) > 2000 or sum(f.file_size for f in files) > 100 * 1024 * 1024:
            raise ValueError("Archive has too many or too large files")
        for entry in files:
            path = Path(entry.filename)
            if (path.is_absolute() or ".." in path.parts or
                (entry.external_attr >> 16) & stat.S_IFMT(0o170000) == stat.S_IFLNK):
                raise ValueError("Archive contains unsafe path")
            if entry.is_dir():
                (destination / path).mkdir(parents=True, exist_ok=True)
            else:
                target = destination / path
                target.parent.mkdir(parents=True,exist_ok=True)
                with package.open(entry) as reader, open(target,"wb") as out:
                    while part := reader.read(65536):
                        out.write(part)
    if not (destination / "server" / "Dockerfile").is_file():
        raise ValueError("Missing server Dockerfile")
    if not (destination / "web" / "index.html").is_file():
        raise ValueError("Missing web content")


def health(expected):
    request = urllib.request.Request("http://127.0.0.1:8787/readyz",
                                     headers={"User-Agent":"simhub-updater/0.9.1"})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                payload = json.load(response)
            if payload.get("ok") and payload.get("version") == expected:
                return True
        except (OSError, ValueError):
            pass
        time.sleep(2)
    return False


def job(version):
    previous = None
    switched = False
    try:
        save("checking", requested=version, error=None)
        info = latest()
        if info["version"] != version:
            raise ValueError("Only the newest stable release can be installed")
        if parse_version(version) <= parse_version(current_tag()):
            raise ValueError("Release is not newer than installed version")
        with tempfile.TemporaryDirectory(prefix="simhub-release-", dir=STATE_DIR) as temp:
            temp = Path(temp)
            meta = manifest(info,temp)
            server = meta.get("server",{})
            asset = server.get("asset")
            checksum = server.get("sha256")
            if (asset != f"simhub-v{version}-source.zip" or not isinstance(checksum,str)
                or not re.fullmatch(r"[0-9a-f]{64}",checksum)
                or not info["assets"].get(asset)):
                raise ValueError("Invalid source manifest")
            save("downloading")
            archive = temp / "source.zip"
            actual = download(info["assets"][asset],archive,MAX_SOURCE_SIZE)
            if actual != checksum:
                raise ValueError("Source SHA-256 mismatch")
            source = temp / "source"
            source.mkdir()
            extract(archive,source)
            save("building")
            run("docker","build","--pull","-f",str(source/"server"/"Dockerfile"),
                "-t","simhub-relay:v"+version,str(source),timeout=1200)
            saved = backup_database()
            previous = "rollback-"+str(int(time.time()))
            current_image_id = image_backup()
            run("docker","image","tag",current_image_id,"simhub-relay:"+previous)
            save("switching",previousTag=previous, backup=saved)
            switched = True
            update_env("v"+version)
            compose("up","-d","--no-build","--force-recreate","simhub",timeout=180)
            save("verifying")
            if not health(version):
                raise RuntimeError("New version failed readiness/version checks")
            save("complete", installed=version, error=None)
    except Exception as exc:
        error = str(exc)[:400]
        if switched and previous:
            try:
                save("rolling_back",error=error)
                update_env(previous)
                compose("up","-d","--no-build","--force-recreate","simhub")
                save("rolled_back",error=error)
            except Exception as rollback_error:
                save("rollback_failed",error=error + "; rollback: " + str(rollback_error)[:300])
        else:
            save("failed",error=error)
    finally:
        RUN_LOCK.release()


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        try:
            raw = self.rfile.readline(8193)
            if len(raw)>8192:
                raise ValueError("Request too large")
            request = json.loads(raw)
            action = request.get("action")
            if action == "status":
                result = {"ok":True, "agentVersion":AGENT_VERSION,
                          "installed":current_tag().removeprefix("v"), **state}
            elif action == "check":
                info = latest()
                result = {"ok":True,"latest":info["version"],
                          "installed":current_tag().removeprefix("v"),
                          "available":parse_version(info["version"])>parse_version(current_tag())}
            elif action == "apply":
                version = request.get("version")
                if not isinstance(version,str) or not VERSION.fullmatch(version):
                    raise ValueError("Invalid version")
                if not RUN_LOCK.acquire(blocking=False):
                    raise ValueError("Another update is running")
                save("queued", requested=version, error=None)
                threading.Thread(target=job,args=(version,),daemon=True).start()
                result = {"ok":True,"phase":"queued", "requested":version}
            else:
                raise ValueError("Unsupported action")
        except Exception as exc:
            result = {"ok":False,"error":str(exc)[:400]}
        try:
            self.wfile.write(json.dumps(result).encode() + b"\n")
        except OSError:
            pass


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads=True
    allow_reuse_address=True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve",action="store_true",required=True)
    args = parser.parse_args()
    STATE_DIR.mkdir(mode=0o750,parents=True,exist_ok=True)
    os.chmod(STATE_DIR,0o750)
    os.chown(STATE_DIR,0,65534)
    if STATUS.exists():
        try:
            state.update(json.loads(STATUS.read_text("utf-8")))
            if state.get("phase") in {"queued","checking","downloading","building",
                                     "backup","switching","verifying","rolling_back"}:
                # A restart cannot be misrepresented as a successful deployment.
                save("interrupted",error="Updater interrupted; manual review required")
        except (ValueError,OSError):
            save("failed",error="Unreadable status file")
    SOCKET.unlink(missing_ok=True)
    with Server(str(SOCKET),Handler) as server:
        os.chown(SOCKET,0,65534)
        os.chmod(SOCKET,0o660)
        server.serve_forever()


if __name__ == "__main__":
    main()
