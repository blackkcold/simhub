import json
import sqlite3
import unittest
from pathlib import Path

import shared_pool


def cipher():
    return {"v": 1, "alg": "A256GCM", "iv": "abcdefghijklmnop",
            "ct": "abcdefghijklmnopqrstuvwxyz0123456789"}


class SharedPoolTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript(Path(__file__).with_name("schema.sql").read_text())
        shared_pool.init(self.db)
        for i in ("a", "b", "c"):
            self.db.execute(
                "INSERT INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                (i, "token-"+i, i, 1))

    def tearDown(self):
        self.db.close()

    def grant(self, device, epoch=1):
        shared_pool.request(self.db,device,True)
        return shared_pool.provision(self.db,{
            "deviceId":device,"epoch":epoch,"keyId":"safe-key-identifier",
            "vaultEnvelope":cipher(),"memberEnvelope":cipher()})

    def test_opt_in_and_admin_grant_are_both_required(self):
        self.assertFalse(shared_pool.enabled(self.db,"a"))
        self.assertTrue(shared_pool.request(self.db,"a",True)["requested"])
        self.assertFalse(shared_pool.enabled(self.db,"a"))
        with self.assertRaises(PermissionError):
            shared_pool.get_messages(self.db,"a",{})
        self.grant("a")
        self.assertTrue(shared_pool.enabled(self.db,"a"))
        self.assertEqual(len(shared_pool.node_status(self.db,"a")["keys"]),1)

    def test_cross_device_ciphertext_only_and_duplicate_ack(self):
        self.grant("a")
        self.grant("b")
        row={"originEventId":"provider-1","occurredAt":1500,"channelId":"ch1",
             "epoch":1,"ciphertext":cipher()}
        self.assertEqual(shared_pool.put_messages(self.db,"a",{"messages":[row]})["inserted"],1)
        ack=shared_pool.put_messages(self.db,"a",{"messages":[row]})
        self.assertEqual(ack["inserted"],0)
        result=shared_pool.get_messages(self.db,"b",{"limit":["50"]})
        self.assertEqual(result["messages"][0]["deviceId"],"a")
        self.assertNotIn("body",json.dumps(result))
        self.assertEqual(result["nextBeforeTime"],1500)

    def test_time_keyset_vs_upload_order(self):
        self.grant("a")
        for i,t in enumerate([2000,1000,1500,2100]):
            shared_pool.put_messages(self.db,"a",{"messages":[{
                "originEventId":str(i),"occurredAt":t,
                "channelId":"sim","epoch":1,"ciphertext":cipher()}]})
        first=shared_pool.get_messages(self.db,"a",{"limit":["2"]})
        self.assertEqual([m["occurredAt"] for m in first["messages"]],[2100,2000])
        second=shared_pool.get_messages(self.db,"a",{"limit":["2"],
            "beforeTime":[str(first["nextBeforeTime"])],
            "beforeSeq":[str(first["nextBeforeSeq"])]})
        self.assertEqual([m["occurredAt"] for m in second["messages"]],[1500,1000])
        updates=shared_pool.get_messages(self.db,"a",{"since":["2"]})
        self.assertEqual(len(updates["messages"]),2)

    def test_revoked_node_cannot_fetch_but_others_can_rotate(self):
        self.grant("a")
        self.grant("b")
        self.assertTrue(shared_pool.revoke(self.db,"a")["rotationRequired"])
        with self.assertRaises(PermissionError):
            shared_pool.get_messages(self.db,"a",{})
        reply=shared_pool.rotate(self.db,{
            "keyId":"new-safe-pool-key","vaultEnvelope":cipher(),
            "members":{"b":cipher()}})
        self.assertEqual(reply["epoch"],2)
        self.assertEqual(len(shared_pool.node_status(self.db,"b")["keys"]),2)
        self.assertEqual(len(shared_pool.node_status(self.db,"a")["keys"]),0)

    def test_offline_member_departure_pauses_new_uploads_until_rotation(self):
        self.grant("a")
        self.grant("b")
        self.assertFalse(shared_pool.rotation_required(self.db))
        shared_pool.member_departure(self.db,"a")
        self.assertTrue(shared_pool.admin_status(self.db)["rotationRequired"])
        self.assertFalse(shared_pool.enabled(self.db,"a"))
        message={"originEventId":"first","occurredAt":1500,
                 "channelId":"sim","epoch":1,"ciphertext":cipher()}
        with self.assertRaises(PermissionError):
            shared_pool.put_messages(self.db,"b",{"messages":[message]})
        with self.assertRaises(ValueError):
            shared_pool.provision(self.db,{"deviceId":"c","keyId":"safe-key-identifier",
                                          "memberEnvelope":cipher()})
        with self.assertRaises(ValueError):
            shared_pool.rotate(self.db,{"expectedEpoch":0,"keyId":"new-safe-pool-key",
                                        "vaultEnvelope":cipher(),"members":{"b":cipher()}})
        shared_pool.rotate(self.db,{"expectedEpoch":1,"keyId":"new-safe-pool-key",
                                    "vaultEnvelope":cipher(),"members":{"b":cipher()}})
        self.assertFalse(shared_pool.rotation_required(self.db))
        message["epoch"]=2
        self.assertEqual(shared_pool.put_messages(self.db,"b",{"messages":[message]})["inserted"],1)

    def test_node_opt_out_requests_rotation_only_when_previously_approved(self):
        self.grant("a")
        self.grant("b")
        shared_pool.request(self.db,"c",True)
        shared_pool.request(self.db,"c",False)
        self.assertFalse(shared_pool.rotation_required(self.db))
        shared_pool.request(self.db,"a",False)
        self.assertTrue(shared_pool.rotation_required(self.db))
        self.assertFalse(shared_pool.enabled(self.db,"a"))
        self.assertTrue(shared_pool.node_status(self.db,"b")["rotationRequired"])

    def test_no_cross_node_message_forgery(self):
        self.grant("a")
        self.assertFalse(shared_pool.enabled(self.db,"c"))
        with self.assertRaises(PermissionError):
            shared_pool.put_messages(self.db,"c",{"messages":[]})
        with self.assertRaises(ValueError):
            shared_pool.put_messages(self.db,"a",{"messages":[{
                "originEventId":"x","occurredAt":1000,
                "channelId":"sim","epoch":7,"ciphertext":cipher()}]})


if __name__=="__main__":
    unittest.main(verbosity=2)
