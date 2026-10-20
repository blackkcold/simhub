#!/usr/bin/env python3
"""End-to-end branding regression: both channels use the same server/UI code."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path
from xml.etree import ElementTree

ROOT=Path(__file__).resolve().parents[1]

class ChannelBrandingTest(unittest.TestCase):
    def test_android_dev_resources_are_isolated(self):
        app=ROOT/"android/app/src"
        self.assertTrue((app/"main/res/drawable/ic_simhub.xml").is_file())
        a=ElementTree.parse(app/"main/res/drawable/ic_simhub.xml").getroot()
        b=ElementTree.parse(app/"devRelease/res/drawable/ic_simhub.xml").getroot()
        self.assertNotEqual(ElementTree.tostring(a),ElementTree.tostring(b))
        self.assertIn("#E87936",ElementTree.tostring(b).decode())
        for suffix in ("values","values-night"):
            d=ElementTree.parse(app/"devRelease/res"/suffix/"colors.xml").getroot()
            colors={e.get("name") for e in d}
            self.assertIn("brand_primary",colors)
            self.assertNotIn("danger",colors)
        gradle=(app.parent/"build.gradle.kts").read_text("utf-8")
        self.assertIn('applicationIdSuffix = ".dev"',gradle)
        self.assertIn('buildConfigField("String", "RELEASE_CHANNEL", "\\\"dev\\\"")',gradle)

    def test_server_runtime_channel_and_public_assets(self):
        for channel in ("stable","dev"):
            with self.subTest(channel=channel), tempfile.TemporaryDirectory() as temp:
                with socket.socket() as s:
                    s.bind(("127.0.0.1",0));port=s.getsockname()[1]
                env=os.environ.copy()
                env.update(SIMHUB_ADMIN_TOKEN="A"*48,SIMHUB_BIND="127.0.0.1",
                           SIMHUB_PORT=str(port),SIMHUB_DB=str(Path(temp)/"relay.db"),
                           SIMHUB_WEB_ROOT=str(ROOT/"web"),SIMHUB_CHANNEL=channel,
                           SIMHUB_REQUIRE_TOTP="false")
                process=subprocess.Popen([sys.executable,str(ROOT/"server/simhub_server.py")],
                    env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                base=f"http://127.0.0.1:{port}"
                try:
                    for _ in range(100):
                        try:
                            urllib.request.urlopen(base+"/readyz",timeout=.2).read()
                            break
                        except Exception:time.sleep(.05)
                    else: self.fail("test relay failed to start")
                    def get(route):
                        with urllib.request.urlopen(base+route,timeout=3) as res:
                            return res.read().decode(),res.headers
                    raw,headers=get("/api/v1/branding")
                    self.assertEqual(json.loads(raw),{"channel":channel})
                    self.assertEqual(headers.get("Cache-Control"),"no-store")
                    raw,headers=get("/manifest.webmanifest")
                    manifest=json.loads(raw)
                    self.assertEqual(headers.get_content_type(),"application/manifest+json")
                    self.assertEqual(headers.get("Cache-Control"),"no-cache")
                    self.assertEqual(manifest["name"],"SIM Hub Dev" if channel=="dev" else "SIM Hub")
                    self.assertEqual(manifest.get("id"),"/simhub-dev" if channel=="dev" else None)
                    self.assertEqual(manifest["theme_color"],"#E87936" if channel=="dev" else "#0c1223")
                    raw,headers=get("/icon.svg")
                    self.assertIn("#F6A66C" if channel=="dev" else "#8177FF",raw)
                    self.assertEqual(headers.get("Cache-Control"),"no-store")
                    page,_=get("/")
                    self.assertIn('data-dev-indicator hidden',page)
                finally:
                    process.terminate()
                    try:process.wait(timeout=5)
                    except subprocess.TimeoutExpired:process.kill();process.wait()

if __name__=="__main__":
    unittest.main(verbosity=2)
