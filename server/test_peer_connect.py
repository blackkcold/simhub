import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from peer_connect import PeerConnect, Rejected, DOMAIN, b64


class PeerConnectTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.now = 100000
        self.turn_secret = "simhub-test-turn-secret-with-32-characters"
        self.service = PeerConnect(str(Path(self.temp.name) / "connect.db"), lambda: self.now, self.turn_secret, ("stun:example.test:3478",), ("turn:example.test:3478",))
        self.a = ec.generate_private_key(ec.SECP256R1())
        self.b = ec.generate_private_key(ec.SECP256R1())
    def tearDown(self):
        self.temp.cleanup()
    @staticmethod
    def identity(key):
        der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        return b64(hashlib.sha256(der).digest()), b64(der)
    def register(self, key, peer, fallback=False, ip="127.0.0.1"):
        identifier, public = self.identity(key)
        challenge = self.service.challenge(ip)["challenge"]
        policy = "P2P_RELAY_FALLBACK" if fallback else "P2P_ONLY"
        transcript = f'{DOMAIN}|register|{challenge}|{peer}|{policy}|{str(fallback).lower()}|{identifier}'
        data = {"identity": public, "peer": peer, "policy": policy, "fallbackConsent": fallback, "challenge": challenge, "signature": b64(key.sign(transcript.encode(), ec.ECDSA(hashes.SHA256())))}
        return self.service.register(ip, data), data
    def actors(self, fallback=False):
        aid, _ = self.identity(self.a)
        bid, _ = self.identity(self.b)
        ar, _ = self.register(self.a, bid, fallback)
        br, _ = self.register(self.b, aid, fallback)
        return self.service.auth(ar["token"]), self.service.auth(br["token"])
    def frame(self, actor, identifier="command-id-1234567890"):
        return {"v": 1, "domain": DOMAIN, "from": actor["identity"], "to": actor["peer"], "id": identifier, "expires": self.now + 120, "iv": b64(b"i" * 12), "ct": b64(b"opaque_ciphertext" * 2)}
    def test_signed_registration_and_challenge_consumption(self):
        bid, _ = self.identity(self.b)
        response, data = self.register(self.a, bid)
        self.assertEqual(response["relayBindingState"], "ACTIVE")
        self.assertEqual(len(response["iceServers"]), 1)
        with self.assertRaises(Rejected):
            self.service.register("127.0.0.1", data)
    def test_signature_permission_binding(self):
        bid, _ = self.identity(self.b)
        _, data = self.register(self.a, bid)
        data.update(challenge=self.service.challenge("127.0.0.1")["challenge"], policy="P2P_RELAY_FALLBACK", fallbackConsent=True)
        with self.assertRaises(Rejected):
            self.service.register("127.0.0.1", data)
    def test_opaque_signal_ack_and_pair_scoping(self):
        a, b = self.actors()
        frame = self.frame(a)
        out = self.service.message(a, {"kind": "signal", "peer": a["peer"], "frame": frame})
        rows = self.service.poll(b)["messages"]
        self.assertEqual(rows[0]["frame"], frame)
        self.assertEqual(self.service.poll(a)["messages"], [])
        self.service.ack(a, out["seq"])
        self.assertEqual(len(self.service.poll(b)["messages"]), 1)
        self.service.ack(b, out["seq"])
        self.assertEqual(self.service.poll(b)["messages"], [])
    def test_mailbox_requires_bilateral_consent(self):
        a, _ = self.actors(False)
        with self.assertRaises(Rejected):
            self.service.message(a, {"kind": "mailbox", "peer": a["peer"], "frame": self.frame(a)})
    def test_mailbox_duplicate_and_expiry(self):
        a, b = self.actors(True)
        data = {"kind": "mailbox", "peer": a["peer"], "frame": self.frame(a)}
        first = self.service.message(a, data)
        duplicate = self.service.message(a, data)
        self.assertEqual(first["seq"], duplicate["seq"])
        self.assertTrue(duplicate["duplicate"])
        self.now += 121
        self.assertEqual(self.service.poll(b)["messages"], [])
        with self.assertRaises(Rejected):
            self.service.message(a, data)
    def test_direction_domain_and_expiry_rejected(self):
        a, _ = self.actors(True)
        for field, value in [("from", a["peer"]), ("to", a["identity"]), ("domain", "simhub-event-v1"), ("expires", self.now+400)]:
            frame = self.frame(a)
            frame[field] = value
            with self.subTest(field=field), self.assertRaises(Rejected):
                self.service.message(a, {"kind": "mailbox", "peer": a["peer"], "frame": frame})
    def test_token_rotation_revocation_and_directory(self):
        a, b = self.actors(True)
        self.service.message(a, {"kind": "mailbox", "peer": a["peer"], "frame": self.frame(a)})
        rows = self.service.directory()["devices"]
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(not x["legacyEnrollment"] for x in rows))
        self.assertTrue(all(x["p2pPairState"] == "ACTIVE" for x in rows))
        self.service.revoke(a)
        self.assertEqual(self.service.poll(b)["messages"], [])
        with self.assertRaises(Rejected):
            self.service.auth("bad-token")
    def test_turn_credentials_only_with_consent(self):
        bid, _ = self.identity(self.b)
        no_turn, _ = self.register(self.a, bid, False)
        self.assertEqual(len(no_turn["iceServers"]), 1)
        with_turn, _ = self.register(self.a, bid, True)
        self.assertEqual(len(with_turn["iceServers"]), 2)
        self.assertIn("credential", with_turn["iceServers"][1])
        # The coturn REST credential is a 20-byte opaque MAC encoded as base64.
        # Avoid duplicating cryptographic implementation in the test itself.
        turn = with_turn["iceServers"][1]
        self.assertEqual(len(base64.b64decode(turn["credential"], validate=True)), 20)
        self.assertLessEqual(int(turn["username"].split(":")[0])-self.now, 300)
    def test_turn_rest_hmac_sha1_known_answer(self):
        # Regression vector for coturn's REST HMAC-SHA1 (not a standalone
        # identity hash). Changing the wire digest breaks TURN authentication.
        # The expected credential was calculated independently of this code.
        bid, _ = self.identity(self.b)
        with patch("peer_connect.secrets.token_hex", return_value="0123456789abcdef0123456789abcdef"):
            response, _ = self.register(self.a, bid, True)
        turn = response["iceServers"][1]
        self.assertEqual(turn["username"], "100300:0123456789abcdef0123456789abcdef")
        self.assertEqual(turn["credential"], "Rb2CyYyKXON8L8nCK8qtqr2HLEQ=")
        self.assertEqual(base64.b64decode(turn["credential"], validate=True), bytes.fromhex(
            "45bd82c98c8a5ce37c2fc9c22bcaadaabd872c44"
        ))

    def test_turn_usernames_do_not_identify_or_link_registrations(self):
        aid, _ = self.identity(self.a)
        bid, _ = self.identity(self.b)
        first, _ = self.register(self.a, bid, True)
        second, _ = self.register(self.a, bid, True)
        usernames = [response["iceServers"][1]["username"] for response in (first, second)]
        for username in usernames:
            self.assertEqual(int(username.split(":", 1)[0]), self.now + 300)
            self.assertNotIn(aid[:16], username)
            self.assertNotIn(bid[:16], username)
        self.assertNotEqual(*usernames)
    def test_capacity_and_rate_limit(self):
        for _ in range(10):
            self.service.challenge("rate-test")
        with self.assertRaises(Rejected) as rejected:
            self.service.challenge("rate-test")
        self.assertEqual(rejected.exception.status, 429)


if __name__ == "__main__":
    unittest.main()
