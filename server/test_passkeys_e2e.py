#!/usr/bin/env python3
"""Cryptographic WebAuthn regression with a synthetic software authenticator.

No real passkey, OTP, private user data or production credentials are used.
"""
import hashlib
import json
import os
import sqlite3
import struct
import unittest
from pathlib import Path

import cbor2
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
import passkeys

ORIGIN="https://admin.example.com"
RP="admin.example.com"
DB=Path(__file__).with_name("schema.sql")


def client_data(kind,challenge):
    return json.dumps({"type":kind,"challenge":challenge,"origin":ORIGIN,
                       "crossOrigin":False},separators=(",",":")).encode()


class PasskeyRealSignatureTests(unittest.TestCase):
    def setUp(self):
        self.con=sqlite3.connect(":memory:")
        self.con.row_factory=sqlite3.Row
        self.con.executescript(DB.read_text())
        self.private=ec.generate_private_key(ec.SECP256R1())
        self.cid=os.urandom(24)

    def tearDown(self):
        self.con.close()

    def register(self):
        start=passkeys.registration_options(self.con,ORIGIN,"admin","sessionhash")
        challenge=start["options"]["challenge"]
        numbers=self.private.public_key().public_numbers()
        public_key=cbor2.dumps({
            1:2,3:-7,-1:1,-2:numbers.x.to_bytes(32,"big"),-3:numbers.y.to_bytes(32,"big")})
        auth_data=(hashlib.sha256(RP.encode()).digest()+bytes([0x45])+struct.pack(">I",0)
                   +bytes(16)+struct.pack(">H",len(self.cid))+self.cid+public_key)
        attestation=cbor2.dumps({"fmt":"none","attStmt":{},"authData":auth_data})
        cred={"id":passkeys.b64(self.cid),"rawId":passkeys.b64(self.cid),"type":"public-key",
              "response":{"clientDataJSON":passkeys.b64(client_data("webauthn.create",challenge)),
                          "attestationObject":passkeys.b64(attestation)}}
        return passkeys.register(self.con,ORIGIN,"admin","sessionhash",start["challengeId"],cred,"Test key")

    def authenticate(self,sign_count=1,corrupt=False):
        start=passkeys.authentication_options(self.con,ORIGIN,"admin","","login")
        challenge=start["options"]["challenge"]
        auth_data=hashlib.sha256(RP.encode()).digest()+bytes([0x05])+struct.pack(">I",sign_count)
        cd=client_data("webauthn.get",challenge)
        signature=self.private.sign(auth_data+hashlib.sha256(cd).digest(),ec.ECDSA(hashes.SHA256()))
        if corrupt:signature=signature[:-1]+bytes([signature[-1]^0xff])
        cred={"id":passkeys.b64(self.cid),"rawId":passkeys.b64(self.cid),"type":"public-key",
              "response":{"clientDataJSON":passkeys.b64(cd),
                          "authenticatorData":passkeys.b64(auth_data),
                          "signature":passkeys.b64(signature),"userHandle":None}}
        return passkeys.authenticate(self.con,ORIGIN,"admin","","login",start["challengeId"],cred)

    def test_register_sign_in_and_monotonic_counter(self):
        key=self.register()
        self.assertEqual(key["label"],"Test key")
        success=self.authenticate()
        self.assertEqual(success["id"],passkeys.b64(self.cid))
        self.assertEqual(self.con.execute("SELECT sign_count FROM admin_passkeys").fetchone()[0],1)
        success=self.authenticate(2)
        self.assertEqual(success["label"],"Test key")

    def test_reject_invalid_signature_and_replay(self):
        self.register()
        with self.assertRaises(Exception):
            self.authenticate(corrupt=True)
        self.assertEqual(self.con.execute("SELECT sign_count FROM admin_passkeys").fetchone()[0],0)


if __name__=="__main__":
    unittest.main(verbosity=2)
