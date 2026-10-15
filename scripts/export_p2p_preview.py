#!/usr/bin/env python3
"""Export an emulator-verified P2P APK in one process.

android-emulator-runner executes each 'script:' line in a fresh shell; never
carry VERSION or other shell variables between those commands.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "android/app/build.gradle.kts"
APK = ROOT / "android/app/build/outputs/apk/p2pPreview/app-p2pPreview.apk"
TEST_RESULTS = ROOT / "android-sandbox-results.txt"
TEST_GUIDE = ROOT / "docs/p2p/TEST_GUIDE.zh-CN.md"
EXPECTED_COUNT = 12


def export_package(root: Path = ROOT) -> Path:
    gradle = (root / GRADLE.relative_to(ROOT)).read_text(encoding="utf-8")
    versions = re.findall(
        r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$',
        gradle, flags=re.MULTILINE,
    )
    if len(versions) != 1:
        raise RuntimeError("APK_VERSION_MISSING_OR_AMBIGUOUS")
    source = root / APK.relative_to(ROOT)
    result_file = root / TEST_RESULTS.relative_to(ROOT)
    guide = root / TEST_GUIDE.relative_to(ROOT)
    if not source.is_file() or source.stat().st_size == 0:
        raise RuntimeError("APK_MISSING")
    if not guide.is_file():
        raise RuntimeError("TEST_GUIDE_MISSING")
    if not result_file.is_file():
        raise RuntimeError("ANDROID_INSTRUMENTATION_EVIDENCE_MISSING")
    results = result_file.read_text(encoding="utf-8")
    expected = rf"(?m)^OK \({EXPECTED_COUNT} tests\)\s*$"
    if not re.search(expected, results) or "FAILURES!!!" in results:
        raise RuntimeError("ANDROID_INSTRUMENTATION_NOT_PASSED")
    name = f"SIMHub-P2P-{versions[0]}-public-preview.apk"
    target = root / name
    shutil.copyfile(source, target)
    shutil.copyfile(guide, root / "P2P-TEST-GUIDE.md")
    digest = hashlib.file_digest(target.open("rb"), "sha256").hexdigest()
    (root / "p2p-apk-sha256.txt").write_text(f"{digest}  {name}\n", encoding="utf-8")
    print(f"PREVIEW_ARTIFACT_VERIFIED version={versions[0]} tests={EXPECTED_COUNT} sha256={digest}")
    return target


if __name__ == "__main__":
    export_package()
