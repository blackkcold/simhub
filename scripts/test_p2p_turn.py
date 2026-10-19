#!/usr/bin/env python3
"""Use the actual connection-service TURN credential generator with real coturn."""
import os
import pathlib
import subprocess
import sys
import tempfile
import time
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "server"))
from peer_connect import PeerConnect, b64, DOMAIN
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
import hashlib

with tempfile.TemporaryDirectory(prefix="simhub-turn-sandbox-") as folder:
    service = PeerConnect(folder + "/connect.db", turn_secret="simhub-sandbox-only-never-production-32-bytes", turn_urls=("turn:127.0.0.1:13478?transport=udp",))
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    identity = b64(hashlib.sha256(der).digest())
    peer = b64(os.urandom(32))
    challenge = service.challenge("sandbox")["challenge"]
    proof = f"{DOMAIN}|register|{challenge}|{peer}|P2P_RELAY_FALLBACK|true|{identity}"
    registration = service.register("sandbox", {"identity": b64(der), "peer": peer, "policy": "P2P_RELAY_FALLBACK", "fallbackConsent": True, "challenge": challenge, "signature": b64(key.sign(proof.encode(), ec.ECDSA(hashes.SHA256())))})
    # The deprecated JS/WebView harness was removed with the native RTC engine.
    # Exercise the *real* coturn server with its own TURN client instead. This
    # performs authenticated UDP allocations and relays application test frames
    # between two TURN clients; no Android WebView or retired script is needed.
    turn = [entry for entry in registration["iceServers"]
            if any(url.startswith("turn:") for url in entry.get("urls", []))]
    assert len(turn) == 1, "The consented test must provide one TURN configuration"
    credentials = turn[0]
    assert credentials.get("username") and credentials.get("credential")
    assert int(credentials["username"].split(":", 1)[0]) <= int(time.time()) + 300
    assert int(credentials["username"].split(":", 1)[0]) > int(time.time())
    time.sleep(1)
    command = ["turnutils_uclient", "-y", "-c", "-n", "3", "-l", "128",
               "-p", "13478", "-u", credentials["username"],
               "-w", credentials["credential"], "127.0.0.1"]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=40)
    except subprocess.TimeoutExpired as error:
        raise AssertionError("coturn test client did not finish in 40 seconds") from error
    if result.returncode != 0:
        raise AssertionError("Authenticated coturn UDP relay failed:\n" +
                             (result.stdout + result.stderr)[-3000:])
    print("coturn REST credential authenticated; TURN UDP relay client completed")
