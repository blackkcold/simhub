"""Global Vault regression tests: singleton initialization, encrypted envelopes, rotation."""
import base64
import json
import sqlite3
import unittest
import uuid

import global_vault

def b64(n, byte=11):
    return base64.urlsafe_b64encode(bytes([byte]) * n).decode().rstrip("=")

def payload(identifier=None, kid=None, count=310000):
    return {"vaultId":identifier or str(uuid.uuid4()), "keyId":kid or b64(16),
        "envelope":{"v":1,"kdf":"PBKDF2-SHA256","iterations":count,
                    "salt":b64(16, 8),"iv":b64(12, 9),"ct":b64(48, 10)}}

class GlobalVaultTest(unittest.TestCase):
    def setUp(self):
        self.con=sqlite3.connect(":memory:")
        self.con.row_factory=sqlite3.Row
        global_vault.init(self.con)

    def tearDown(self):
        self.con.close()

    def test_one_vault_across_multiple_clients(self):
        self.assertFalse(global_vault.get(self.con)["initialized"])
        body=payload()
        created=global_vault.initialize(self.con,body)
        self.assertEqual(created["generation"],1)
        self.assertEqual(created["keyId"],body["keyId"])
        for _ in range(3):
            remote=global_vault.get(self.con)
            self.assertEqual(remote["vaultId"],body["vaultId"])
            self.assertEqual(remote["envelope"],body["envelope"])
        with self.assertRaisesRegex(ValueError,"already initialized"):
            global_vault.initialize(self.con,payload())

    def test_rewrap_same_root_only_and_optimistic_lock(self):
        body=payload()
        global_vault.initialize(self.con,body)
        changed=payload(body["vaultId"],body["keyId"],600000)
        changed["expectedGeneration"]=1
        out=global_vault.rewrap(self.con,changed)
        self.assertEqual(out["generation"],2)
        self.assertEqual(global_vault.get(self.con)["envelope"]["iterations"],600000)
        with self.assertRaisesRegex(ValueError,"concurrent"):
            global_vault.rewrap(self.con,changed)
        changed["expectedGeneration"]=2
        changed["keyId"]=b64(16,12)
        with self.assertRaisesRegex(ValueError,"identity changed"):
            global_vault.rewrap(self.con,changed)

    def test_reject_invalid_envelope(self):
        cases=[{"keyId":"broken"}, {"vaultId":"not-a-uuid"},
               {"envelope":{"v":1}}, {"envelope":{**payload()["envelope"],"salt":"!"}},
               {"envelope":{**payload()["envelope"],"ct":b64(47)}},
               {"envelope":{**payload()["envelope"],"iterations":100}},
               {"envelope":{**payload()["envelope"],"iterations":True}}]
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    global_vault.initialize(self.con,{**payload(),**case})
                self.assertFalse(global_vault.status(self.con)["initialized"])

    def test_global_key_not_stored_in_plaintext(self):
        body=payload()
        global_vault.initialize(self.con,body)
        raw=json.dumps(global_vault.get(self.con),sort_keys=True)
        self.assertNotIn("masterKey",raw)
        self.assertNotIn("passphrase",raw)

if __name__=="__main__":
    unittest.main()
