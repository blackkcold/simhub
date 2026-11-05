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

    def test_release_outputs_wait_for_independent_current_head_checks(self):
        for name in ("release.yml", "release-dev.yml"):
            workflow=Path(".github/workflows",name).read_text("utf-8")
            gate=workflow.split("  release-gates:\n",1)[1].split("\n  build-image:",1)[0]
            self.assertIn("scripts/verify_release_checks.py",gate)
            for job in ("build-image", "sign"):
                block=re.split(r"\n  (?=[A-Za-z0-9_-]+:)",workflow.split("  "+job+":\n",1)[1],maxsplit=1)[0]
                self.assertIn("needs: [validate, release-gates]",block)

    def test_dev_pre_release_contract_does_not_mutate_stable_identity(self):
        dev=Path(".github/workflows/release-dev.yml").read_text("utf-8")
        stable=Path(".github/workflows/release.yml").read_text("utf-8")
        build=Path("android/app/build.gradle.kts").read_text("utf-8")
        self.assertIn("      - develop",dev)
        self.assertIn("'.github/dev-release/publish.trigger'",dev)
        self.assertIn("--prerelease",dev)
        self.assertIn('"channel":"dev"',dev)
        self.assertIn('com.blackkcold.simhub.dev',dev)
        self.assertIn("release-dev.yml@refs/heads/develop",dev)
        self.assertIn("gh release create",stable)
        self.assertNotIn("--prerelease",stable)
        self.assertIn("scripts/check_stable_boundary.py",stable)
        self.assertIn('create("devRelease")',build)
        self.assertIn('applicationIdSuffix = ".dev"',build)



if __name__ == "__main__":
    unittest.main()
