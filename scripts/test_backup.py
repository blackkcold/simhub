#!/usr/bin/env python3
"""Exercise manual/updater backup safety against real SQLite WAL snapshots."""
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path
from backup_tools import backup_lock, verify_checksum, verify_database, write_checksum

SCRIPTS = Path(__file__).resolve().parent
DOCKER = '''#!/usr/bin/env python3
import os,pathlib,shutil,subprocess,sys
root=pathlib.Path(os.environ["BACKUP_TEST_ROOT"])
args=sys.argv[1:]
assert args[0]=="compose", args
if args[1:5]==["exec","-T","simhub","python3"]:
    program=sys.stdin.read().replace("/data/",str(root/"data")+"/")
    target=str(root/"data"/pathlib.Path(args[-1]).name)
    raise SystemExit(subprocess.run([sys.executable,"-",target],input=program,text=True).returncode)
if args[1]=="cp":
    source=root/"data"/pathlib.Path(args[2].split(":",1)[1]).name
    if os.environ.get("BACKUP_TEST_FAIL_COPY"):
        pathlib.Path(args[3]).write_bytes(b"incomplete snapshot")
        raise SystemExit(1)
    shutil.copyfile(source,args[3])
elif args[1:6]==["exec","-T","simhub","rm","-f"]:
    (root/"data"/pathlib.Path(args[-1]).name).unlink(missing_ok=True)
else:
    raise SystemExit("Unexpected synthetic Docker command")
'''


class BackupTests(unittest.TestCase):
    def fixture(self, root):
        scripts = root / "scripts"
        scripts.mkdir()
        for name in ("backup.sh", "backup_tools.py", "verify_backup.py"):
            shutil.copyfile(SCRIPTS / name, scripts / name)
        binary = root / "bin"
        binary.mkdir()
        docker = binary / "docker"
        docker.write_text(DOCKER)
        docker.chmod(0o700)
        (root / "data").mkdir()
        connection = sqlite3.connect(root / "data/simhub.db")
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA user_version=13")
        connection.execute("CREATE TABLE events (id INTEGER PRIMARY KEY,ciphertext TEXT)")
        connection.execute("INSERT INTO events VALUES (1, 'encrypted-snapshot-fixture')")
        connection.commit()
        environment = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"],
                       "BACKUP_TEST_ROOT": str(root)}
        return connection, environment

    def test_online_snapshot_unique_private_and_rehearsable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            live, environment = self.fixture(root)
            try:
                for _ in range(2):
                    result = subprocess.run(["bash", str(root / "scripts/backup.sh"), str(root / "backups")],
                                            env=environment, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                backups = list((root / "backups").glob("*.db"))
                self.assertEqual(len(backups), 2)
                self.assertEqual((root / "backups").stat().st_mode & 0o777, 0o700)
                for backup in backups:
                    self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(backup.with_name(backup.name + ".sha256").stat().st_mode & 0o777, 0o600)
                    verify_checksum(backup)
                    result = verify_database(backup, restore_check=True)
                    self.assertTrue(result["restoreVerified"])
                    with sqlite3.connect(backup) as restored:
                        self.assertEqual(restored.execute("SELECT ciphertext FROM events").fetchone()[0],
                                         "encrypted-snapshot-fixture")
                self.assertFalse(list((root / "data").glob("simhub-*.db")))
                self.assertEqual(live.execute("SELECT COUNT(*) FROM events").fetchone()[0], 1)
            finally:
                live.close()

    def test_failed_copy_leaves_no_completed_or_remote_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            live, environment = self.fixture(root)
            try:
                result = subprocess.run(["bash", str(root / "scripts/backup.sh"), str(root / "backups")],
                                        env={**environment, "BACKUP_TEST_FAIL_COPY": "1"},
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(list((root / "backups").iterdir()), [])
                self.assertFalse(list((root / "data").glob("simhub-*.db")))
            finally:
                live.close()

    def test_manual_and_updater_share_nonblocking_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with backup_lock(root):
                with self.assertRaisesRegex(RuntimeError, "Another backup"):
                    with backup_lock(root):
                        self.fail("Second updater acquired an active lock")
                result = subprocess.run(["flock", "-n", str(root / ".simhub-backup.lock"), "true"])
                self.assertNotEqual(result.returncode, 0)
            with backup_lock(root):
                self.assertEqual((root / ".simhub-backup.lock").stat().st_mode & 0o777, 0o600)

    def test_corrupt_or_modified_snapshots_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "snapshot.db"
            path.write_bytes(b"not a SQLite database")
            write_checksum(path)
            verify_checksum(path)
            with self.assertRaises(sqlite3.DatabaseError):
                verify_database(path, restore_check=True)
            path.write_bytes(b"altered backup")
            with self.assertRaises(ValueError):
                verify_checksum(path)


if __name__ == "__main__":
    unittest.main()
