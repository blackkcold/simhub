#!/usr/bin/env python3
"""Fail CI before a release when Android, Relay, trigger and notes disagree."""
from pathlib import Path
import re
import unittest


class ReleaseContractTests(unittest.TestCase):
    def test_version_trigger_and_changelog_agree(self):
        android=Path("android/app/build.gradle.kts").read_text(encoding="utf-8")
        relay=Path("server/simhub_server.py").read_text(encoding="utf-8")
        workflow=Path(".github/workflows/release.yml").read_text(encoding="utf-8")
        changelog=Path("CHANGELOG.md").read_text(encoding="utf-8")
        android_version=re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"',android)
        relay_version=re.search(r'^APP_VERSION\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"',relay,re.M)
        self.assertIsNotNone(android_version)
        self.assertIsNotNone(relay_version)
        version=android_version.group(1)
        self.assertEqual(relay_version.group(1),version)
        trigger=Path(f".github/release/v{version}.trigger")
        self.assertTrue(trigger.is_file())
        self.assertEqual(trigger.read_text(encoding="utf-8").splitlines()[0].strip(),version)
        self.assertRegex(changelog,rf"(?m)^## v{re.escape(version)}\b")
        self.assertIn("'.github/release/v*.trigger'",workflow)
        self.assertIn("git diff --name-only \"$BEFORE\" \"$GITHUB_SHA\"",workflow)
        self.assertNotIn("head -n1 .github/release/v0.17.1.trigger",workflow)


if __name__ == "__main__":
    unittest.main()
