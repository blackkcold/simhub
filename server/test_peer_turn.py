"""TURN REST credentials are optional, ephemeral and constrained to operator URLs."""
import base64
import hashlib
import hmac
import os
import unittest
from unittest.mock import patch
from server import peer_turn

class PeerTurnTests(unittest.TestCase):
    def test_disabled_by_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(peer_turn.configured_ice(1700000000), [])

    def test_issues_short_lived_coturn_credentials(self):
        cfg = {"SIMHUB_PEER_TURN_SECRET": "a" * 48,
               "SIMHUB_PEER_TURN_URLS": "turn:turn.example.org:3478?transport=udp,turns:turn.example.org:5349?transport=tcp"}
        with patch.dict(os.environ, cfg, clear=True):
            entry, = peer_turn.configured_ice(1700000000)
        self.assertEqual(entry["urls"], cfg["SIMHUB_PEER_TURN_URLS"].split(","))
        self.assertTrue(entry["username"].startswith("1700000600:"))
        expected = base64.b64encode(hmac.digest(cfg["SIMHUB_PEER_TURN_SECRET"].encode(),
                                                 entry["username"].encode(), "sha1")).decode()
        self.assertEqual(entry["credential"], expected)

    def test_rejects_untrusted_urls_and_weak_secret(self):
        for bad in ("https://bad.invalid", "turn:127.0.0.1:3478/path",
                    "turn://evil.invalid", "turn:user@host.invalid", "turn:host.invalid,https://evil.invalid"):
            with patch.dict(os.environ, {"SIMHUB_PEER_TURN_SECRET": "a" * 48,
                                        "SIMHUB_PEER_TURN_URLS": bad}, clear=True):
                self.assertEqual(peer_turn.configured_ice(1700000000), [])
        with patch.dict(os.environ, {"SIMHUB_PEER_TURN_SECRET": "short",
                                    "SIMHUB_PEER_TURN_URLS": "turn:turn.example.org:3478"}, clear=True):
            self.assertEqual(peer_turn.configured_ice(1700000000), [])

if __name__ == "__main__":
    unittest.main()
