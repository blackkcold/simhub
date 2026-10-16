#!/usr/bin/env python3
"""Validate signed-image deployment bundle contents and channel isolation."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from scripts.package_deployment import build

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


if __name__ == "__main__":
    unittest.main()
