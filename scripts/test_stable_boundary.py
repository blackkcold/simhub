#!/usr/bin/env python3
"""Release-channel boundary regressions: real main tree and negative fixtures."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT=Path(__file__).with_name("check_stable_boundary.py")
spec=importlib.util.spec_from_file_location("stable_boundary", SCRIPT)
boundary=importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


class StableBoundaryTests(unittest.TestCase):
    def test_reject_android_peer_and_native_webrtc(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            code=root/"android"/"app"/"src"/"main"/"java"
            code.mkdir(parents=True)
            (code/"PeerActivity.kt").write_text("experimental",encoding="utf-8")
            build=root/"android"/"app"/"build.gradle.kts"
            build.write_text('implementation("io.github.webrtc-sdk:android:150")',encoding="utf-8")
            found=boundary.violations(root)
            self.assertEqual(len(found),2)

    def test_reject_backend_p2p_module(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"server").mkdir()
            (root/"server"/"p2p_signaling.py").write_text("",encoding="utf-8")
            self.assertEqual(len(boundary.violations(root)),1)


if __name__=="__main__":
    unittest.main()
