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


    def test_dev_feed_filters_stable_and_draft_without_reusing_stable_cache(self):
        def entry(version, pre=True, draft=False):
            tag="v"+version
            return {"tag_name":tag, "draft":draft, "prerelease":pre,
                    "published_at":"2026-10-16T03:00:00Z",
                    "assets":[{"name":"update-manifest.json",
                               "browser_download_url":"https://github.com/blackkcold/simhub/releases/download/"
                               +tag+"/update-manifest.json"}]}
        feeds=[entry("0.17.2",False),entry("0.17.2-dev.8"),
               entry("0.17.2-dev.10"),entry("0.17.2-dev.20",draft=True)]
        old=bridge._cache
        try:
            bridge._cache=(0,None)
            with patch.object(bridge,"CHANNEL","dev"),patch.object(
                    bridge,"CHANNEL_VERSION",bridge.DEV_VERSION),patch.object(
                    bridge.urllib.request,"urlopen",return_value=io.BytesIO(json.dumps(feeds).encode())):
                found=bridge.release(force=True)
                self.assertEqual(found["version"],"0.17.2-dev.10")
                self.assertEqual(found["channel"],"dev")
                self.assertEqual(bridge.version_tuple("0.17.2-dev.10"),(0,17,2,10))
                self.assertEqual(bridge.version_tuple("0.17.2"),(0,0,0))
                self.assertTrue(bridge.DEV_VERSION.fullmatch("0.17.2-dev.10"))
                self.assertFalse(bridge.DEV_VERSION.fullmatch("0.17.2"))
        finally:
            bridge._cache=old

    def test_dev_manifest_rejects_stable_channel_and_apk_package(self):
        version="0.17.2-dev.10"
        asset="simhub-agent-v"+version+"-release.apk"
        uri="https://github.com/blackkcold/simhub/releases/download/v"+version+"/"
        info={"version":version,"tag":"v"+version,
              "assets":{"update-manifest.json":uri+"update-manifest.json",asset:uri+asset}}
        meta={"schemaVersion":1,"channel":"dev","release":version,
              "android":{"versionName":version,"versionCode":5200010,
                         "packageName":"com.blackkcold.simhub.dev",
                         "asset":asset,"sha256":"b"*64}}
        with patch.object(bridge,"CHANNEL","dev"),patch.object(
                bridge,"CHANNEL_VERSION",bridge.DEV_VERSION),patch.object(
                bridge,"release",return_value=info),patch.object(
                bridge.urllib.request,"urlopen",return_value=io.BytesIO(json.dumps(meta).encode())):
            self.assertEqual(bridge.android_ota()["versionName"],version)
        meta["channel"]="stable"
        with patch.object(bridge,"CHANNEL","dev"),patch.object(
                bridge,"release",return_value=info),patch.object(
                bridge.urllib.request,"urlopen",return_value=io.BytesIO(json.dumps(meta).encode())):
            with self.assertRaises(ValueError):
                bridge.manifest()

    def test_release_manual_refresh_bypasses_background_cache(self):
        """A fresh manual request must not remain on a 15-minute-old release."""
        def payload(version):
            return io.BytesIO(json.dumps({
                "tag_name": "v" + version, "draft": False,
                "prerelease": False, "published_at": "2026-10-13T04:30:23Z",
                "assets": [{"name": "update-manifest.json",
                            "browser_download_url": "https://github.com/blackkcold/simhub/releases/download/v"
                            + version + "/update-manifest.json"}]
            }).encode())
        old_cache = bridge._cache
        try:
            bridge._cache = (0, None)
            with patch.object(bridge.time, "monotonic", return_value=100), patch.object(
                    bridge.urllib.request, "urlopen", return_value=payload("0.16.4")) as fetch:
                first = bridge.release()
                self.assertEqual(first["version"], "0.16.4")
                self.assertFalse(first["fromCache"])
                self.assertEqual(bridge.release()["version"], "0.16.4")
                self.assertTrue(bridge.release()["fromCache"])
                self.assertEqual(fetch.call_count, 1)
            with patch.object(bridge.time, "monotonic", return_value=107), patch.object(
                    bridge.urllib.request, "urlopen", return_value=payload("0.16.5")) as fetch:
                latest = bridge.release(force=True)
                self.assertEqual(latest["version"], "0.16.5")
                self.assertFalse(latest["fromCache"])
                self.assertEqual(fetch.call_count, 1)
        finally:
            bridge._cache = old_cache

    def test_failed_refresh_preserves_previous_release_without_claiming_success(self):
        old_cache = bridge._cache
        try:
            bridge._cache = (100, {"version": "0.16.4", "tag": "v0.16.4"})
            with patch.object(bridge.time, "monotonic", return_value=150), patch.object(
                    bridge.urllib.request, "urlopen", side_effect=OSError("GitHub offline")):
                with self.assertRaises(OSError):
                    bridge.release(force=True)
            self.assertEqual(bridge._cache[1]["version"], "0.16.4")
        finally:
            bridge._cache = old_cache

    def test_release_metadata_is_bounded(self):
        old_cache = bridge._cache
        try:
            bridge._cache = (0, None)
            with patch.object(bridge.urllib.request, "urlopen",
                              return_value=io.BytesIO(b"x" * 262145)):
                with self.assertRaises(ValueError):
                    bridge.release(force=True)
        finally:
            bridge._cache = old_cache

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
