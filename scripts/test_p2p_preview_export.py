#!/usr/bin/env python3
"""Contract tests for publishing only emulator-verified preview APKs."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from export_p2p_preview import export_package

class ExportPreviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        build = self.root / "android/app/build"
        build.mkdir(parents=True)
        (build.parent / "build.gradle.kts").write_text(
            'defaultConfig {\n    versionName = "0.17.2"\n}', encoding="utf-8")
        self.apk = build / "outputs/apk/p2pPreview/app-p2pPreview.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"fixture-apk")
        guide = self.root / "docs/p2p/TEST_GUIDE.zh-CN.md"
        guide.parent.mkdir(parents=True)
        guide.write_text("test guide", encoding="utf-8")
        self.evidence = self.root / "android-sandbox-results.txt"
        self.evidence.write_text("............\nTime: 33.327\n\nOK (15 tests)\n",
                                 encoding="utf-8")

    def test_verified_apk_and_digest(self):
        out = export_package(self.root)
        self.assertEqual(out.name, "SIMHub-P2P-0.17.2-public-preview.apk")
        self.assertEqual(out.read_bytes(), self.apk.read_bytes())
        self.assertEqual(
            (self.root / "p2p-apk-sha256.txt").read_text(),
            f"{hashlib.sha256(self.apk.read_bytes()).hexdigest()}  {out.name}\n")
        self.assertEqual((self.root / "P2P-TEST-GUIDE.md").read_text(), "test guide")

    def test_bad_or_absent_test_evidence_never_exports(self):
        for text in ("FAILURES!!!\nTests run: 13, Failures: 1\n",
                     "OK (14 tests)\n", "OK (15 tests)\nFAILURES!!!\n"):
            with self.subTest(text=text):
                self.evidence.write_text(text)
                with self.assertRaisesRegex(RuntimeError, "INSTRUMENTATION_NOT_PASSED"):
                    export_package(self.root)
                self.assertFalse((self.root / "SIMHub-P2P-0.17.2-public-preview.apk").exists())

    def test_missing_apk_never_exports(self):
        self.apk.unlink()
        with self.assertRaisesRegex(RuntimeError, "APK_MISSING"):
            export_package(self.root)

    def test_version_must_be_unambiguous(self):
        (self.root / "android/app/build.gradle.kts").write_text(
            'versionName = "0.17.2"\nversionName = "0.17.3"\n')
        with self.assertRaisesRegex(RuntimeError, "APK_VERSION_MISSING_OR_AMBIGUOUS"):
            export_package(self.root)


if __name__ == "__main__":
    unittest.main()
