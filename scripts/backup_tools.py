"""Private, consistent SQLite backup validation shared by deployment tools."""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import os
import re
import sqlite3
import tempfile
from pathlib import Path


@contextlib.contextmanager
def backup_lock(root: Path):
    """Serialize manual and updater backups for one deployment directory."""
    descriptor = os.open(root / ".simhub-backup.lock", os.O_WRONLY | os.O_CREAT, 0o600)
    try:
        os.fchmod(descriptor, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("Another backup is running for this deployment") from error
        yield
    finally:
        os.close(descriptor)


def digest(path: Path) -> str:
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1048576), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def write_checksum(path: Path) -> Path:
    target = path.with_name(path.name + ".sha256")
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(digest(path) + "  " + path.name + "\n")
    return target


def verify_checksum(path: Path) -> None:
    target = path.with_name(path.name + ".sha256")
    text = target.read_text("utf-8")
    match = re.fullmatch(r"([a-f0-9]{64})  ([^\r\n]+)\n?", text)
    if not match or match[2] != path.name or match[1] != digest(path):
        raise ValueError("Backup SHA-256 does not match its checksum file")


def _snapshot(connection: sqlite3.Connection) -> dict:
    connection.execute("PRAGMA query_only=ON")
    if connection.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
        raise ValueError("Backup failed SQLite integrity verification")
    if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise ValueError("Backup failed SQLite foreign-key verification")
    schema = connection.execute("PRAGMA user_version").fetchone()[0]
    rows = connection.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name").fetchall()
    tables = [name for kind, name, _ in rows if kind == "table" and not name.startswith("sqlite_")]
    if schema <= 0 or not tables:
        raise ValueError("Backup has no initialized SIM Hub database schema")
    counts = {name: connection.execute('SELECT COUNT(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0]
              for name in tables}
    return {"schemaVersion": schema, "schema": rows, "counts": counts}


def verify_database(path: Path, *, restore_check: bool = False) -> dict:
    """Open read-only; optionally restore into a disposable private directory.

    This never starts Relay, contacts devices, rewrites the source backup or
    decrypts event payloads. The JSON result contains only aggregate metadata.
    """
    path = path.resolve(strict=True)
    if not path.is_file():
        raise ValueError("Expected a SQLite backup file")
    with contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)) as source:
        expected = _snapshot(source)
        if restore_check:
            with tempfile.TemporaryDirectory(prefix="simhub-restore-check-") as temporary:
                restored = Path(temporary) / "restored.db"
                descriptor = os.open(restored, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(descriptor)
                with contextlib.closing(sqlite3.connect(restored)) as target:
                    source.backup(target)
                    if _snapshot(target) != expected:
                        raise ValueError("Restored database does not match the backup")
    return {"ok": True, "schemaVersion": expected["schemaVersion"],
            "tables": len(expected["counts"]), "rows": sum(expected["counts"].values()),
            "restoreVerified": restore_check}
