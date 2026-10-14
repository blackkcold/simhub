#!/usr/bin/env python3
"""Use the actual connection-service TURN credential generator with real coturn."""
import json
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
    service = PeerConnect(folder + "/connect.db", turn_secret="sandbox-only-never-production", turn_urls=("turn:127.0.0.1:13478?transport=udp",))
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    identity = b64(hashlib.sha256(der).digest())
    peer = b64(os.urandom(32))
    challenge = service.challenge("sandbox")["challenge"]
    proof = f"{DOMAIN}|register|{challenge}|{peer}|P2P_RELAY_FALLBACK|true|{identity}"
    registration = service.register("sandbox", {"identity": b64(der), "peer": peer, "policy": "P2P_RELAY_FALLBACK", "fallbackConsent": True, "challenge": challenge, "signature": b64(key.sign(proof.encode(), ec.ECDSA(hashes.SHA256())))})
    # Allow the subprocess started by CI to bind; not a network-availability claim.
    time.sleep(1)
    subprocess.run(["node", "scripts/test_p2p_webrtc.mjs"], check=True, env={**os.environ, "P2P_TEST_FORCE_RELAY": "1", "P2P_TEST_ICE": json.dumps(registration["iceServers"])})
