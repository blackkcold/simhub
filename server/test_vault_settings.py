"""Encrypted settings cannot leak labels or silently overwrite another client."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest

import global_vault
from test_global_vault import b64, payload
import vault_settings


class VaultSettingsTests(unittest.TestCase):
    def setUp(self):
        self.con=sqlite3.connect(":memory:")
        self.con.row_factory=sqlite3.Row
        global_vault.init(self.con);vault_settings.init(self.con)
        self.vault=payload()
        self.body={"vaultId":self.vault["vaultId"],"keyId":self.vault["keyId"],"expectedRevision":0,
                   "ciphertext":{"v":2,"alg":"A256GCM","kid":self.vault["keyId"],"iv":b64(12),"ct":b64(48)}}

    def tearDown(self):
        self.con.close()

    def test_initialized_identity_and_revision_conflicts_are_explicit(self):
        self.assertFalse(vault_settings.get(self.con)["initialized"])
        with self.assertRaises(vault_settings.SettingsConflict):vault_settings.put(self.con,self.body)
        global_vault.initialize(self.con,self.vault)
        self.assertEqual(vault_settings.get(self.con)["revision"],0)
        created=vault_settings.put(self.con,self.body)
        self.assertEqual(created["revision"],1)
        self.assertEqual(created["ciphertext"],self.body["ciphertext"])
        with self.assertRaises(vault_settings.SettingsConflict):vault_settings.put(self.con,self.body)
        second=vault_settings.put(self.con,{**self.body,"expectedRevision":1})
        self.assertEqual(second["revision"],2)
        with self.assertRaises(vault_settings.SettingsConflict):
            vault_settings.put(self.con,{**self.body,"expectedRevision":2,"vaultId":payload()["vaultId"]})

    def test_password_rewrap_does_not_change_settings_identity_or_revision(self):
        global_vault.initialize(self.con,self.vault)
        before=vault_settings.put(self.con,self.body)
        changed=payload(self.vault["vaultId"],self.vault["keyId"],600000)
        changed["expectedGeneration"]=1
        global_vault.rewrap(self.con,changed)
        self.assertEqual(vault_settings.get(self.con),before)

    def test_only_bounded_ciphertext_is_accepted(self):
        global_vault.initialize(self.con,self.vault)
        invalid=[{**self.body,"phoneOverrides":{"phone":"plaintext"}},
                 {**self.body,"expectedRevision":True},
                 {**self.body,"ciphertext":{**self.body["ciphertext"],"label":"plaintext"}},
                 {**self.body,"ciphertext":{**self.body["ciphertext"],"kid":b64(16,2)}},
                 {**self.body,"ciphertext":{**self.body["ciphertext"],"iv":b64(11)}},
                 {**self.body,"ciphertext":{**self.body["ciphertext"],"ct":b64(45001)}},
                 {**self.body,"ciphertext":{**self.body["ciphertext"],"ct":"invalid!"}}]
        for body in invalid:
            with self.subTest(fields=list(body)):
                with self.assertRaises(ValueError):vault_settings.put(self.con,body)
                self.assertEqual(vault_settings.get(self.con)["revision"],0)
        vault_settings.put(self.con,self.body)
        saved=self.con.execute("SELECT ciphertext_json FROM vault_settings").fetchone()[0]
        self.assertEqual(set(json.loads(saved)),{"v","alg","kid","iv","ct"})
        self.assertNotIn("plaintext",saved)

    def test_concurrent_clients_cannot_both_replace_same_revision(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"settings.db"
            with sqlite3.connect(path) as con:
                con.row_factory=sqlite3.Row
                global_vault.init(con);vault_settings.init(con)
                global_vault.initialize(con,self.vault)
                vault_settings.put(con,self.body)
            start=threading.Barrier(2)
            def write(byte):
                with sqlite3.connect(path,timeout=5) as con:
                    con.row_factory=sqlite3.Row
                    start.wait(timeout=5)
                    try:
                        body={**self.body,"expectedRevision":1,
                              "ciphertext":{**self.body["ciphertext"],"ct":b64(48,byte)}}
                        return vault_settings.put(con,body)["revision"]
                    except vault_settings.SettingsConflict:return "conflict"
            with ThreadPoolExecutor(max_workers=2) as pool:
                result=list(pool.map(write,(20,21)))
            self.assertCountEqual(result,[2,"conflict"])
            with sqlite3.connect(path) as con:
                con.row_factory=sqlite3.Row
                self.assertEqual(vault_settings.get(con)["revision"],2)


if __name__=="__main__":unittest.main()
