"""Host updater security invariants, no Docker/network access required."""
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location("simhub_updater",Path(__file__).with_name("simhub_updater.py"))
updater=importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class UpdaterTests(unittest.TestCase):
    def test_version(self):
        self.assertEqual(updater.parse_version("v0.9.1"),(0,9,1))
        self.assertEqual(updater.parse_version("1.2.0"),(1,2,0))
        self.assertEqual(updater.parse_version("v1.0.0-beta"),(0,0,0))

    def test_reject_traversal(self):
        with tempfile.TemporaryDirectory() as d:
            arc=Path(d)/"malicious.zip"
            with zipfile.ZipFile(arc,"w") as z:z.writestr("../escape","payload")
            with self.assertRaises(ValueError):
                updater.extract(arc,Path(d)/"target")

    def test_reject_invalid_release_metadata(self):
        with patch.object(updater,"fetch_json",return_value={"tag_name":"v0.9.1",
                                                              "draft":False,"prerelease":True,"published_at":"now"}):
            with self.assertRaises(ValueError):
                updater.latest()

    def test_manifest_requires_hash(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(updater,"download",side_effect=lambda url,path,maxsize:Path(path).write_text(
                    json.dumps({"schemaVersion":1,"release":"0.9.9","channel":"stable"}))):
                with self.assertRaises(ValueError):
                    updater.manifest({"version":"0.9.1","assets":{"update-manifest.json":"https://github.com/blackkcold/simhub/releases/download/v0.9.1/update-manifest.json"}},Path(d))


if __name__=="__main__":unittest.main()
