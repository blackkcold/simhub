"""Release metadata must describe the inspected APK, not a parallel formula."""
import tempfile
import unittest
from pathlib import Path

from scripts.apk_release_metadata import inspect_badging, validate


class ApkReleaseMetadataTests(unittest.TestCase):
    def test_actual_apk_identity_and_digest_are_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"synthetic-signed-apk")
            badging = "package: name='com.blackkcold.simhub.dev' versionCode='5400008' versionName='0.17.4-dev.8' platformBuildVersionName='37'\n"
            data = inspect_badging(badging, apk, "dev", "0.17.4-dev.8")
            self.assertEqual(data["versionCode"], 5400008)
            self.assertEqual(validate(data, apk, "dev", "0.17.4-dev.8"), 5400008)
            with self.assertRaises(ValueError):
                validate(data, apk, "stable", "0.17.4")
            with self.assertRaises(ValueError):
                validate({**data, "versionName": "0.17.4-dev.7"}, apk, "dev", "0.17.4-dev.8")
            apk.write_bytes(b"different-apk")
            with self.assertRaises(ValueError):
                validate(data, apk, "dev", "0.17.4-dev.8")

    def test_invalid_badging_or_version_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"synthetic")
            for badging in ("", "package: name='wrong' versionCode='1' versionName='0.17.4' ",
                            "package: name='com.blackkcold.simhub' versionCode='0' versionName='0.17.4' "):
                with self.assertRaises(ValueError):
                    inspect_badging(badging, apk, "stable", "0.17.4")


if __name__ == "__main__":
    unittest.main()
