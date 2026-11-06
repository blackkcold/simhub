#!/usr/bin/env python3
"""Validate signed-image deployment bundle contents and channel isolation."""
import json
import importlib.util
import io
import sqlite3
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from package_deployment import build

DIGEST = "ghcr.io/blackkcold/simhub-relay@sha256:" + "a" * 64


class DeploymentBundleTests(unittest.TestCase):
    def test_both_channels_are_disjoint_and_self_contained(self):
        with tempfile.TemporaryDirectory() as tmp:
            archives = []
            for channel, version, identity in (
                ("stable", "0.17.4", "release.yml@refs/heads/main"),
                ("dev", "0.17.4-dev.4", "release-dev.yml@refs/heads/develop"),
            ):
                out = Path(tmp) / f"{channel}.zip"
                build(version, channel, DIGEST, out)
                archives.append(out)
                with zipfile.ZipFile(out) as arc:
                    names = set(arc.namelist())
                    self.assertIn("scripts/deploy-verified.sh", names)
                    self.assertIn("scripts/install-updater.sh", names)
                    self.assertIn("docker-compose.yml", names)
                    self.assertIn("DEPLOY-README.txt", names)
                    self.assertNotIn(".env", names)
                    self.assertNotIn("center-config.json", names)
                    data = json.loads(arc.read("deployment.json"))
                    self.assertEqual(data["channel"], channel)
                    self.assertEqual(data["version"], version)
                    self.assertEqual(data["image"], DIGEST)
                    self.assertTrue(data["cosignIdentity"].endswith(identity))
                before = out.read_bytes()
                build(version, channel, DIGEST, out)
                self.assertEqual(out.read_bytes(), before)
            self.assertNotEqual(archives[0].read_bytes(), archives[1].read_bytes())

    def test_reject_mismatched_channel_or_mutable_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "bundle.zip"
            for version, channel, image in (
                ("0.17.4-dev.1", "stable", DIGEST),
                ("0.17.4", "dev", DIGEST),
                ("0.17.4", "stable", "ghcr.io/blackkcold/simhub-relay:latest"),
                ("0.17.4", "unknown", DIGEST),
            ):
                with self.assertRaises(ValueError):
                    build(version, channel, image, target)

    def test_extracted_bundle_updates_and_rolls_back_without_source_checkout(self):
        """Execute the packaged updater, mocking only host/network boundaries.

        Unlike manifest-only tests this follows preflight, signed pull, a real
        SQLite backup, environment switching, readiness failure and rollback.
        No Docker daemon, real release, secrets or production volume is used.
        """
        for channel in ("stable", "dev"):
            for fail_readiness in (False, True):
                with self.subTest(channel=channel, rollback=fail_readiness), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp) / "deployment"
                    root.mkdir()
                    version = "0.17.4" + ("-dev.4" if channel == "dev" else "")
                    next_version = "0.18.0" + ("-dev.5" if channel == "dev" else "")
                    image = "ghcr.io/blackkcold/simhub-relay@sha256:" + "b" * 64
                    bundle = Path(tmp) / "release.zip"
                    build(version, channel, DIGEST, bundle)
                    with zipfile.ZipFile(bundle) as archive:
                        archive.extractall(root)
                    self.assertFalse((root / "server").exists())
                    port = "8788" if channel == "dev" else "8787"
                    env = root / ".env"
                    env.write_text(f"SIMHUB_CHANNEL={channel}\nSIMHUB_HOST_PORT={port}\n"
                                   f"SIMHUB_INSTALLED_VERSION={version}\nSIMHUB_IMAGE_REF={DIGEST}\n")
                    spec = importlib.util.spec_from_file_location("bundle_updater", root / "scripts/simhub_updater.py")
                    updater = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(updater)
                    updater.STATE_DIR.mkdir(mode=0o700)
                    remote_files = {}
                    calls = []
                    with sqlite3.connect(Path(tmp) / "live.db") as connection:
                        connection.execute("PRAGMA user_version=13")
                        connection.execute("CREATE TABLE events (ciphertext TEXT)")
                        connection.execute("INSERT INTO events VALUES ('encrypted-fixture')")

                    def host(*argv, **kwargs):
                        calls.append(argv)
                        if argv[:2] == ("cosign", "verify"):
                            self.assertIn("--certificate-identity=" + (
                                updater.DEV_CERT_IDENTITY if channel == "dev" else updater.CERT_IDENTITY), argv)
                            return "verified test image"
                        if argv[:2] == ("docker", "info"):
                            return '["name=rootless"]'
                        if argv[:2] == ("docker", "pull") or argv[:3] == ("docker", "image", "tag"):
                            return ""
                        if argv[:3] == ("docker", "image", "inspect"):
                            return json.dumps([image])
                        if argv[:2] == ("docker", "inspect"):
                            return "sha256:" + "c" * 64
                        self.assertEqual(argv[:2], ("docker", "compose"))
                        args = argv[6:]
                        if args[:1] == ("config",):
                            values = dict(line.split("=", 1) for line in env.read_text().splitlines())
                            return json.dumps({"services": {"simhub": {
                                "image": values["SIMHUB_IMAGE_REF"], "read_only": True,
                                "environment": {"SIMHUB_CHANNEL": channel},
                                "ports": [{"target": 8787, "published": port, "host_ip": "127.0.0.1"}],
                            }}})
                        if args[:1] == ("ps",):
                            return "synthetic-container"
                        if args[:1] == ("up",):
                            self.assertIn("--no-build", args)
                            self.assertIn("never", args)
                            return ""
                        if args[:1] == ("cp",):
                            import shutil
                            shutil.copyfile(remote_files[args[1].split(":", 1)[1]], args[2])
                            return ""
                        if args[:4] == ("exec", "-T", "simhub", "rm"):
                            remote_files.pop(args[-1]).unlink()
                            return ""
                        self.assertEqual(args[:5], ("exec", "-T", "simhub", "python3", "-c"))
                        if "PRAGMA user_version" in args[5]:
                            return "13"
                        # Execute the backup's SQLite work on a disposable live DB.
                        import ast
                        program = ast.parse(args[5])
                        remote = program.body[2].value.args[0].value
                        target = Path(tmp) / Path(remote).name
                        remote_files[remote] = target
                        with sqlite3.connect(Path(tmp) / "live.db") as source, sqlite3.connect(target) as dest:
                            source.backup(dest)
                        return ""

                    info = {"version": next_version, "assets": {"update-manifest.json": "fixture://manifest"}}
                    manifest = {"schemaVersion": 1, "channel": channel, "release": next_version,
                                "server": {"dbSchema": 13, "image": image}}
                    readiness = [False, True] if fail_readiness else [True]
                    with patch.object(updater.os, "geteuid", return_value=1000), \
                            patch.object(updater, "run", side_effect=host), \
                            patch.object(updater, "latest", return_value=info), \
                            patch.object(updater, "fetch_json", return_value=manifest), \
                            patch.object(updater, "health", side_effect=readiness):
                        updater.require_rootless()
                        self.assertEqual(updater.current_version(), version)
                        self.assertTrue(updater.RUN_LOCK.acquire(False))
                        updater.job(next_version)
                    status = json.loads(updater.STATUS.read_text())
                    self.assertEqual(status["phase"], "rolled_back" if fail_readiness else "complete", status)
                    self.assertEqual(updater.current_version(), version if fail_readiness else next_version)
                    self.assertEqual(env.stat().st_mode & 0o777, 0o600)
                    with sqlite3.connect(status["backup"]) as backup:
                        self.assertEqual(backup.execute("PRAGMA integrity_check").fetchone()[0], "ok")
                        self.assertEqual(backup.execute("SELECT ciphertext FROM events").fetchone()[0], "encrypted-fixture")
                    self.assertFalse(remote_files)
                    self.assertEqual(sum(call[:2] == ("cosign", "verify") for call in calls), 2)

    def test_bundle_rejects_cross_channel_or_public_listener(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = root / "release.zip"
            build("0.17.4", "stable", DIGEST, bundle)
            with zipfile.ZipFile(bundle) as archive:
                archive.extractall(root)
            (root / ".env").write_text("SIMHUB_CHANNEL=dev\nSIMHUB_HOST_PORT=8788\n")
            spec = importlib.util.spec_from_file_location("bundle_updater", root / "scripts/simhub_updater.py")
            updater = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(updater)
            with patch.object(updater, "compose") as compose:
                with self.assertRaisesRegex(ValueError, "bundle identity"):
                    updater.validate_deployment()
                compose.assert_not_called()
            (root / ".env").write_text("SIMHUB_CHANNEL=stable\nSIMHUB_HOST_PORT=8787\n")
            config = {"services": {"simhub": {"image": DIGEST, "read_only": True,
                      "environment": {"SIMHUB_CHANNEL": "stable"},
                      "ports": [{"target": 8787, "published": "8787", "host_ip": "0.0.0.0"}]}}}
            with patch.object(updater, "compose", return_value=json.dumps(config)):
                with self.assertRaisesRegex(ValueError, "loopback"):
                    updater.validate_deployment()
            with patch.object(updater.urllib.request, "urlopen", side_effect=OSError("offline")):
                self.assertEqual(updater.current_version(), "0.0.0")


if __name__ == "__main__":
    unittest.main()
