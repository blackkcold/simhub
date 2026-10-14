#!/usr/bin/env python3
"""Executable host sandbox for real JVM crypto and isolated connection service."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    suites = ["server.test_peer_connect"]
    result = subprocess.run([sys.executable, "-m", "unittest", "-v", *suites], cwd=ROOT, env={**os.environ, "PYTHONPATH": str(ROOT / "server")})
    if result.returncode:
        return result.returncode
    with tempfile.TemporaryDirectory(prefix="simhub-peer-jvm-") as folder:
        source = ROOT / "android/app/src/main/java/com/blackkcold/simhub/p2p"
        runner = ROOT / "scripts/PeerCryptoSandbox.java"
        subprocess.run(["javac", "-d", folder, str(source / "PeerCrypto.java"), str(source / "ConnectionPolicy.java"), str(runner)], check=True)
        output = subprocess.check_output(["java", "-cp", folder, "PeerCryptoSandbox"], text=True)
    summary = {"jvm": json.loads(output), "server": "passed", "real_android_devices": "not_run", "public_service": "not_operated", "legacy_relay": "see_ci_regression_results"}
    (ROOT / "p2p-sandbox-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
