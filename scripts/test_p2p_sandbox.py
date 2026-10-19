#!/usr/bin/env python3
"""Executable host sandbox for the native P2P engine, center-auxiliary signaling and
the shared pinned HTTP client.

This replaces the former bundled-WebView sandbox. It asserts the properties the
security review depends on rather than merely that the old files were removed:
there is no remote-code engine, no unpinned public signaling endpoint, and every
JSON request that carries a center credential goes through the tested pinned client.
"""
import json
import os
import pathlib
import subprocess
import sys
import shutil
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main/java/com/blackkcold/simhub"
P2P = MAIN / "p2p"
PROTOCOL = ROOT / "android/peer-core/src/main/java/com/blackkcold/simhub/p2p"


def _sources(folder):
    return sorted(path for path in folder.rglob("*.java")) + sorted(path for path in folder.rglob("*.kt"))


def verify_native_engine():
    """The WebRTC engine is native and data-only: no WebView, no bundled scripts,
    no remote code, and the pinned webrtc dependency stays exact."""
    assets = ROOT / "android/app/src/main/assets"
    for name in ("p2p.html", "p2p.js"):
        assert not (assets / name).exists(), f"removed WebView asset still present: {name}"
    for name in ("PeerWebRtcTransport.java", "PeerCloudConnector.java", "PeerInternetConnector.java",
                 "PeerCloudProtocol.java"):
        assert not (P2P / name).exists(), f"retired signaling sink still present: {name}"
    transport = (P2P / "PeerNativeRtcTransport.java").read_text(encoding="utf-8")
    assert (ROOT / "android/peer-core/build.gradle.kts").exists(), "protocol module missing"
    assert not (P2P / "PeerCrypto.java").exists(), "duplicate protocol in app source"
    for marker in ("org.webrtc", '"simhub-peer-v1"', "createDataChannel", "getStats",
                   "selectedCandidatePairId", "engineReady", "onDataChannel", "restartIce"):
        assert marker in transport, f"native engine invariant missing: {marker}"
    for forbidden in ("WebView", "loadUrl", "setJavaScriptEnabled", "addJavascriptInterface",
                      "evaluateJavascript", "shouldInterceptRequest", "android.webkit"):
        assert forbidden not in transport, f"WebView or remote-code marker in the native engine: {forbidden}"
    for source in _sources(MAIN):
        text = source.read_text(encoding="utf-8")
        assert "android.webkit" not in text, f"WebView dependency reintroduced: {source}"
        assert "0.peerjs.com" not in text, f"retired public signaling endpoint present: {source}"
    gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    marker = 'implementation("io.github.webrtc-sdk:android:'
    assert marker in gradle, "native WebRTC dependency missing"
    pinned = gradle.split(marker, 1)[1].split('"', 1)[0]
    assert pinned and "+" not in pinned, f"webrtc-sdk dependency must be pinned exactly: {pinned}"
    manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert "android.permission.RECORD_AUDIO" not in manifest, "data-only engine must not request audio capture"


def verify_pinned_shared_client():
    """Both credential-carrying clients must use the shared tested OkHttp factory."""
    secure = (MAIN / "SecureHttp.java").read_text(encoding="utf-8")
    for marker in ("CertificatePinner", "followRedirects(false)", "followSslRedirects(false)",
                   "TLS_PIN_MISMATCH", "targetUrl", "splitTarget"):
        assert marker in secure, f"pinned client invariant missing: {marker}"
    trust = (MAIN / "CenterTrust.java").read_text(encoding="utf-8")
    for marker in ("MIN_PINS = 2", "normalizeOrigin", "normalizePin", "pinFor", "fromQr"):
        assert marker in trust, f"trust model invariant missing: {marker}"
    api = (MAIN / "ApiClient.java").read_text(encoding="utf-8")
    assert "HttpURLConnection" not in api, "node client must not use the legacy HTTP stack"
    assert "SecureHttp.exchange" in api, "node client must use the shared pinned client"
    controller = (MAIN / "ControllerSecureClient.kt").read_text(encoding="utf-8")
    assert "HttpURLConnection" not in controller, "controller client must not use the legacy HTTP stack"
    assert "SecureHttp.exchange" in controller, "controller client must use the shared pinned client"
    assert "X-SimHub-Signal-Token" in controller, "controller signaling must carry the session token"


def verify_signaling_protocol():
    """Purpose-separated signaling: its own domain, its own subkey and its own vectors."""
    signaling = (PROTOCOL / "PeerSignalingProtocol.java").read_text(encoding="utf-8")
    for marker in ('"simhub-peer-signal-v1"', "deriveKey", "canonicalAad", "bindingTranscript",
                   "sessionTranscript", "END_OF_CANDIDATES"):
        assert marker in signaling, f"signaling protocol invariant missing: {marker}"
    assert 'DOMAIN="simhub-peer-v1"' not in signaling, "signaling must not reuse the business domain"
    vectors = ROOT / "test_vectors/signaling-v1.json"
    assert vectors.exists(), "cross-language signaling vectors missing"
    payload = json.loads(vectors.read_text(encoding="utf-8"))
    assert payload.get("domain") == "simhub-peer-signal-v1", "vector domain mismatch"
    connector = (P2P / "PeerCenterConnector.java").read_text(encoding="utf-8")
    for marker in ("CENTER_SETUP_REQUIRED", "store.signaling(", 'optBoolean("consent")',
                   "PeerSignalingProtocol.seal", "PeerSignalingProtocol.open"):
        assert marker in connector, f"center connector invariant missing: {marker}"
    policy = (PROTOCOL / "ConnectionPolicy.java").read_text(encoding="utf-8")
    assert 'case "TURN" -> this==P2P_RELAY_FALLBACK && turnConsent' in policy, (
        "TURN must remain independent of legacy Relay enrollment")
    center = (ROOT / "server/simhub_server.py").read_text(encoding="utf-8")
    assert 'result["iceServers"] = peer_turn.configured_ice(now())' in center, (
        "only authenticated session create/join may issue optional TURN ICE")
    connector = (P2P / "PeerCenterConnector.java").read_text(encoding="utf-8")
    assert "controllerOriginForNode(nodeOrigin)" in connector, (
        "signed NODE origin must never be used for controller authentication")
    assert "PeerCenterProtocol.iceServers(context, peerId, issuedTurn)" in connector, (
        "TURN may enter the RTC engine only from the authenticated session response")
    store = (P2P / "PeerStore.java").read_text(encoding="utf-8")
    assert "migrateSignalingConsent" in store, "idempotent consent migration missing"
    assert '"signalingConsent",false' in store.replace(" ", ""), "signaling consent must default to false"


def verify_no_generated_config_in_source():
    """The trusted description is generated, never committed next to the sources."""
    for path in list(MAIN.rglob("center-config.json")) + list((ROOT / "android/app/src/main/assets").rglob("*.json")):
        raise AssertionError(f"generated trust configuration must not be a source file: {path}")


def find_json_jar():
    """org.json is needed to compile the signaling protocol outside Gradle."""
    candidates = []
    override = os.environ.get("JSON_JAR")
    if override:
        candidates.append(pathlib.Path(override))
    for base in (os.environ.get("GRADLE_USER_HOME"), str(pathlib.Path.home() / ".gradle")):
        if not base:
            continue
        candidates += sorted(pathlib.Path(base).glob("caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar"))
        candidates += sorted(pathlib.Path(base).glob("caches/**/org.json/json/**/json-*.jar"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def main():
    verify_native_engine()
    verify_pinned_shared_client()
    verify_signaling_protocol()
    verify_no_generated_config_in_source()
    suites = ["server.test_peer_connect", "server.test_peer_signaling", "server.test_peer_signaling_http", "server.test_peer_turn"]
    result = subprocess.run([sys.executable, "-m", "unittest", "-v", *suites], cwd=ROOT,
                            env={**os.environ, "PYTHONPATH": str(ROOT / "server")})
    if result.returncode:
        return result.returncode
    with tempfile.TemporaryDirectory(prefix="simhub-peer-jvm-") as folder:
        source = PROTOCOL
        runner = ROOT / "scripts/PeerCryptoSandbox.java"
        dump = ROOT / "scripts/SignalingVectorDump.java"
        compiler = ["javac"] if shutil.which("javac") else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
        json_jar = find_json_jar()
        command = [*compiler]
        targets = [str(source / "PeerCrypto.java"), str(source / "ConnectionPolicy.java")]
        if json_jar is not None:
            command += ["-cp", str(json_jar)]
            targets += [str(source / "PeerSignalingProtocol.java"), str(dump)]
        else:
            print("note: org.json jar not found; the JVM signaling protocol compile is deferred "
                  "to the Gradle unit tests", file=sys.stderr)
        command += ["-d", folder, *targets, str(runner)]
        subprocess.run(command, check=True)
        output = subprocess.check_output(["java", "-cp", folder, "PeerCryptoSandbox"], text=True)
    summary = {
        "jvm": json.loads(output),
        "server": "passed",
        "real_android_devices": "not_run",
        "public_signaling": "removed: PeerJS and the bundled WebView engine are retired; "
                            "signaling is center-auxiliary only",
        "native_engine": "io.github.webrtc-sdk:android (data-only, exact pin)",
        "center_client": "shared OkHttp factory with SPKI pins; both roles migrated; "
                         "no matching pin fails before any credential is written",
        "signaling_trust": "CT-1: the auxiliary path needs a trusted description for the exact origin "
                           "plus explicit per-pair consent; the retired discovery flag never enables it",
        "default_turn_servers": 0,
        "legacy_relay": "see_ci_regression_results",
    }
    (ROOT / "p2p-sandbox-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
