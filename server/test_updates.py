"""Release metadata, version matching and ignored-version security regressions."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import update_bridge as bridge


def fake_manifest(version="0.9.1", package="com.blackkcold.simhub", checksum=None):
    return {"schemaVersion":1,"release":version,"channel":"stable",
            "android":{"versionName":version,"versionCode":21,
                       "packageName":package,
                       "asset":"simhub-agent-v0.9.1-release.apk",
                       "sha256":checksum or "a"*64}}


class UpdateBridgeTests(unittest.TestCase):
    def test_compare_components(self):
        self.assertEqual(bridge.version_tuple("v0.9.1"),(0,9,1))
        self.assertLess(bridge.version_tuple("0.9.0"),bridge.version_tuple("0.9.1"))
        self.assertEqual(bridge.version_tuple("bad"),(0,0,0))

    def test_manifest_to_android_ota(self):
        assets={"update-manifest.json":"https://github.com/blackkcold/simhub/releases/download/v0.9.1/update-manifest.json",
                "simhub-agent-v0.9.1-release.apk":"https://github.com/blackkcold/simhub/releases/download/v0.9.1/simhub-agent-v0.9.1-release.apk"}
        info={"tag":"v0.9.1","version":"0.9.1","assets":assets}
        with patch.object(bridge,"release",return_value=info), patch.object(
                bridge.urllib.request,"urlopen",return_value=io.BytesIO(json.dumps(fake_manifest()).encode())):
            ota=bridge.android_ota()
        self.assertEqual(ota["versionCode"],21)
        self.assertEqual(ota["sha256"],"a"*64)
        self.assertTrue(ota["available"])

    def test_mismatched_manifest_fails_closed(self):
        info={"tag":"v0.9.1","version":"0.9.1",
              "assets":{"update-manifest.json":"https://github.com/blackkcold/simhub/releases/download/v0.9.1/update-manifest.json"}}
        with patch.object(bridge,"release",return_value=info), patch.object(
                bridge.urllib.request,"urlopen",return_value=io.BytesIO(json.dumps(fake_manifest("0.9.2")).encode())):
            with self.assertRaises(ValueError):
                bridge.manifest()

    def test_untrusted_package_fails_closed(self):
        with patch.object(bridge,"manifest",return_value=fake_manifest(package="com.example.malicious")), patch.object(
                bridge,"release",return_value={"version":"0.9.1","assets":{"simhub-agent-v0.9.1-release.apk":"https://example.com"}}):
            with self.assertRaises(ValueError):
                bridge.android_ota()

    def test_ignore_persistence_and_input_validation(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(bridge,"IGNORE_FILE",Path(tmp)/"ignore.json"):
            bridge.set_ignored("0.9.1")
            self.assertEqual(bridge.ignored(),"0.9.1")
            with self.assertRaises(ValueError):
                bridge.set_ignored("../malicious")
            bridge.set_ignored(None)
            self.assertIsNone(bridge.ignored())


if __name__=="__main__":
    unittest.main()
