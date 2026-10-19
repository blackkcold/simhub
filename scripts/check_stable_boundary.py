#!/usr/bin/env python3
"""Fail closed if experimental P2P code is introduced into the Stable release."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def violations(root: Path) -> list[str]:
    problems = []
    android = root / "android" / "app" / "src" / "main"
    if android.exists():
        for file in android.rglob("*"):
            if file.is_file() and (
                file.name.startswith(("Peer", "P2P")) or
                "/p2p/" in file.as_posix().lower() or
                "webrtc" in file.name.lower()
            ):
                problems.append("Experimental Android module in Stable: " + file.relative_to(root).as_posix())
    # A library module is still experimental even when the old source paths
    # have been removed from the application. Guard both sources and wiring.
    for module in ("peer-core", "peer-transport", "peer-android"):
        folder = root / "android" / module
        if folder.exists():
            problems.append("Experimental peer library in Stable: android/" + module)
    settings = root / "android" / "settings.gradle.kts"
    if settings.exists() and any(name in settings.read_text("utf-8")
                                 for name in ('":peer-core"', '":peer-transport"', '":peer-android"')):
        problems.append("Experimental peer Gradle include in Stable")
    gradle = root / "android" / "app" / "build.gradle.kts"
    if gradle.exists():
        text = gradle.read_text("utf-8")
        for dependency in ("io.github.webrtc-sdk:android", "org.webrtc:google-webrtc", 'project(":peer-core")'):
            if dependency in text:
                problems.append("WebRTC dependency included in Stable Android build: " + dependency)
    server = root / "server"
    if server.exists():
        for file in server.glob("*p2p*"):
            if file.is_file():
                problems.append("Experimental P2P backend included in Stable image: " + file.name)
        for file in server.glob("*peer*"):
            if file.is_file():
                problems.append("Experimental Peer backend included in Stable image: " + file.name)
    return problems

if __name__ == "__main__":
    issues = violations(ROOT)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        sys.exit(1)
    print("Stable boundary verified: no experimental P2P module or WebRTC dependency")
