"""Regression tests: controller identity, zero-knowledge grant, replay and revocation."""
import base64
import hashlib
import json
import sqlite3
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
import controller_auth

def b64(x):
    return base64.urlsafe_b64encode(x).decode().rstrip("=")

def pub(pair):
    return b64(pair.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo))

class ControllerAuthTest(unittest.TestCase):
    def setUp(self):
        self.con=sqlite3.connect(":memory:")
        self.con.row_factory=sqlite3.Row
        self.con.execute("""CREATE TABLE admin_sessions(
          token_hash TEXT PRIMARY KEY,controller_id TEXT NOT NULL DEFAULT '')""")
        controller_auth.init(self.con)
        self.id_key=ec.generate_private_key(ec.SECP256R1())
        self.ecdh=ec.generate_private_key(ec.SECP256R1())
        self.now=1_770_000_000
        self.ip="127.0.0.1"
        self.fingerprint="admin-v1"
        self.pair=controller_auth.start(self.con,{"label":"Pixel",
            "identityPublic":pub(self.id_key),"exchangePublic":pub(self.ecdh)},
            self.ip,self.now)

    def envelope(self):
        return {"v":1,"epk":pub(ec.generate_private_key(ec.SECP256R1())),
            "salt":b64(bytes(range(16))),"iv":b64(bytes(range(12))),
            "ct":b64(bytes(range(48)))}

    def approve(self):
        return controller_auth.approve(self.con,{"requestId":self.pair["requestId"],
            "pairingCode":self.pair["pairingCode"],"vaultEnvelope":self.envelope()},
            self.now+1,self.fingerprint)

    def test_controller_approval_requires_code_and_keeps_vault_sealed(self):
        pending=controller_auth.pending(self.con,self.now)
        self.assertEqual(pending[0]["requestId"],self.pair["requestId"])
        self.assertNotIn("pairingCode",pending[0])
        with self.assertRaises(ValueError):
            controller_auth.approve(self.con,{"requestId":self.pair["requestId"],
                "pairingCode":"000000" if self.pair["pairingCode"]!="000000" else "000001",
                "vaultEnvelope":self.envelope()},self.now,self.fingerprint)
        approved=self.approve()
        self.assertEqual(controller_auth.poll(self.con,self.pair["requestId"],
                         self.ip,self.now+2,self.pair["pollToken"])["controllerId"],approved["controllerId"])
        with self.assertRaises(ValueError):
            controller_auth.approve(self.con,{"requestId":self.pair["requestId"],
                "pairingCode":self.pair["pairingCode"],"vaultEnvelope":self.envelope()},
                self.now+2,self.fingerprint)

    def test_signature_one_time_replay_and_revoke(self):
        approved=self.approve()
        cid=approved["controllerId"]
        challenge=controller_auth.challenge(self.con,cid,self.now+2,self.fingerprint)["challenge"]
        payload=f"simhub-controller-login-v1|{cid}|{challenge}".encode()
        signature=self.id_key.sign(payload,ec.ECDSA(hashes.SHA256()))
        request={"controllerId":cid,"challenge":challenge,"signature":b64(signature)}
        self.assertEqual(controller_auth.verify_login(self.con,request,self.now+3,self.fingerprint),cid)
        with self.assertRaises(ValueError):
            controller_auth.verify_login(self.con,request,self.now+3,self.fingerprint)
        self.con.execute("INSERT INTO admin_sessions VALUES(?,?)",("session-hash",cid))
        self.assertTrue(controller_auth.revoke(self.con,cid,self.now+4))
        self.assertEqual(self.con.execute("SELECT COUNT(*) FROM admin_sessions").fetchone()[0],0)
        with self.assertRaises(ValueError):
            controller_auth.challenge(self.con,cid,self.now+5,self.fingerprint)

    def test_approved_pairing_survives_ip_handover(self):
        self.approve()
        result=controller_auth.poll(self.con,self.pair["requestId"],"198.51.100.8",
                                    self.now+2,self.pair["pollToken"])
        self.assertEqual(result["state"],"approved")

    def test_invalid_public_key_and_untrusted_poll(self):
        with self.assertRaises(ValueError):
            controller_auth.start(self.con,{"label":"bad","identityPublic":b64(b"key"),
                "exchangePublic":pub(self.ecdh)},self.ip,self.now)
        with self.assertRaises(ValueError):
            controller_auth.poll(self.con,self.pair["requestId"],"203.0.113.5",self.now,"invalid")

if __name__=="__main__":
    unittest.main()
