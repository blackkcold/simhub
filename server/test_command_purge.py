"""Explicit SMS deletion cannot erase durable at-most-once command identity."""
import json
import sqlite3
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import simhub_server as server
import test_simhub as api_fixture


class CommandPurgeHttpTests(unittest.TestCase):
    # Reuse the real HTTP fixture without inheriting or rerunning its test cases.
    setUpClass = classmethod(api_fixture.ApiTest.setUpClass.__func__)
    tearDownClass = classmethod(api_fixture.ApiTest.tearDownClass.__func__)
    req = api_fixture.ApiTest.req
    enroll = api_fixture.ApiTest.enroll
    cipher = staticmethod(api_fixture.ApiTest.cipher)

    def command(self, node, **changes):
        stamp=server.now();cid=str(uuid.uuid4())
        body={"commandId":cid,"idempotencyKey":cid,"type":"sms.send",
              "createdAt":stamp,"expiresAt":stamp+3600,"ciphertext":self.cipher(2)}
        body.update(changes)
        self.assertEqual(self.req("POST",f'/api/v1/devices/{node["deviceId"]}/commands',body)[0],201)
        return body

    def poll(self, node):
        code,response,_=self.req("GET",f'/api/v1/devices/{node["deviceId"]}/commands/pending',
            device_token=node["deviceToken"],admin=False)
        self.assertEqual(code,200,response)
        return response["commands"]

    def test_lost_acceptance_response_purge_and_original_retry_cannot_dispatch_again(self):
        node=self.enroll("Lost response");did=node["deviceId"]
        original=self.command(node)
        # The Controller has not recorded the successful POST response. The
        # node has already obtained the ciphertext, so deletion cannot promise
        # that the radio did not send it.
        self.assertEqual(self.poll(node)[0]["commandId"],original["commandId"])
        self.assertEqual(self.req("DELETE",f"/api/v1/devices/{did}/sms")[0],200)
        code,status,_=self.req("GET",f'/api/v1/devices/{did}/commands/{original["commandId"]}')
        self.assertEqual(code,200)
        self.assertTrue(status["command"]["purged"])
        self.assertEqual(status["command"]["state"],"outcome_unknown")
        for envelope in (original,dict(original,commandId=str(uuid.uuid4())),
                         dict(original,idempotencyKey=str(uuid.uuid4()))):
            code,response,_=self.req("POST",f"/api/v1/devices/{did}/commands",envelope)
            self.assertEqual(code,200,response)
            self.assertTrue(response["duplicate"])
            self.assertTrue(response["purged"])
            self.assertFalse(response["enqueued"])
            self.assertEqual(response["commandId"],original["commandId"])
            self.assertEqual(response["state"],"outcome_unknown")
        self.assertEqual(self.poll(node),[])
        self.assertEqual(self.req("GET",f'/api/v1/devices/wrong-node/commands/{original["commandId"]}')[0],404)
        self.assertEqual(self.req("GET",f'/api/v1/devices/{did}/commands/{original["commandId"]}',admin=False)[0],401)
        other=self.enroll("Independent identity scope")
        code,response,_=self.req("POST",f'/api/v1/devices/{other["deviceId"]}/commands',original)
        self.assertEqual(code,409)
        self.assertEqual(response["error"],"command_conflict")
        self.assertNotIn("state",response)
        # Idempotency keys are per-device; another node's new command remains
        # valid when it has its own globally unique command ID.
        self.command(other,idempotencyKey=original["idempotencyKey"])

    def test_purge_preserves_dispositions_without_content_or_recent_history(self):
        node=self.enroll("Metadata only");did=node["deviceId"]
        states=("queued","dispatched","submitted","sent","delivered","succeeded",
                "failed","rejected","expired","outcome_unknown")
        commands={state:self.command(node) for state in states}
        unrelated=self.command(node,type="device.refresh_state")
        removed_result="DELETE-ME body +15555551212"
        with sqlite3.connect(self.db) as con:
            for state,command in commands.items():
                con.execute("UPDATE commands SET state=?,result_json=? WHERE id=?",
                    (state,json.dumps({"reason":removed_result}),command["commandId"]))
        code,purged,_=self.req("DELETE",f"/api/v1/devices/{did}/sms")
        self.assertEqual(code,200)
        self.assertEqual(purged["cancelledCommands"],len(states))
        for before,command in commands.items():
            code,response,_=self.req("GET",f'/api/v1/devices/{did}/commands/{command["commandId"]}')
            self.assertEqual(code,200)
            expected={"queued":"rejected","dispatched":"outcome_unknown","submitted":"outcome_unknown"}.get(before,before)
            self.assertEqual(response["command"]["state"],expected)
            self.assertEqual(response["command"]["result"],{"reason":"sms_history_purged","purged":True})
            self.assertNotIn("ciphertext",json.dumps(response))
            self.assertNotIn(removed_result,json.dumps(response))
        with sqlite3.connect(self.db) as con:
            columns={row[1] for row in con.execute("PRAGMA table_info(command_tombstones)")}
            self.assertFalse(columns & {"ciphertext_json","result_json","phone","body","recipient"})
            rows=con.execute("SELECT * FROM command_tombstones WHERE device_id=?",(did,)).fetchall()
            self.assertEqual(len(rows),len(states))
            self.assertNotIn(removed_result,str(rows))
            self.assertNotIn(self.cipher(2)["ct"],str(rows))
            self.assertEqual(con.execute("SELECT COUNT(*) FROM commands WHERE device_id=?",(did,)).fetchone()[0],1)
        recent=self.req("GET","/api/v1/commands/recent?limit=50")[1]["commands"]
        self.assertFalse({entry["commandId"] for entry in recent} & {cmd["commandId"] for cmd in commands.values()})
        self.assertEqual([cmd["commandId"] for cmd in self.poll(node)],[unrelated["commandId"]])

    def test_authenticated_late_ack_retires_without_resurrecting_deleted_payload(self):
        node=self.enroll("Late receipt");did=node["deviceId"]
        sent=self.command(node);self.poll(node)
        queued=self.command(node)
        self.assertEqual(self.req("DELETE",f"/api/v1/devices/{did}/sms")[0],200)
        path=f'/api/v1/devices/{did}/commands/{sent["commandId"]}/ack'
        self.assertEqual(self.req("POST",path,{"state":"sent"},admin=False)[0],401)
        code,response,_=self.req("POST",path,{"state":"sent","result":{"reason":"erased body +15555551212"}},
            device_token=node["deviceToken"],admin=False)
        self.assertEqual(code,200,response)
        self.assertTrue(response["ok"]);self.assertTrue(response["purged"])
        self.assertEqual(response["currentState"],"sent")
        for state,flag in (("submitted","ignored"),("sent","duplicate"),("delivered",None)):
            code,response,_=self.req("POST",path,{"state":state},device_token=node["deviceToken"],admin=False)
            self.assertEqual(code,200,response)
            if flag:self.assertTrue(response[flag])
        code,response,_=self.req("POST",f'/api/v1/devices/{did}/commands/{queued["commandId"]}/ack',
            {"state":"sent"},device_token=node["deviceToken"],admin=False)
        self.assertEqual(code,200)
        self.assertTrue(response["ignored"])
        self.assertEqual(response["currentState"],"rejected")
        with sqlite3.connect(self.db) as con:
            self.assertEqual(con.execute("SELECT COUNT(*) FROM commands WHERE device_id=?",(did,)).fetchone()[0],0)
            self.assertEqual(con.execute("SELECT state FROM command_tombstones WHERE id=?",(sent["commandId"],)).fetchone()[0],"delivered")
        self.assertEqual(self.poll(node),[])


class CommandPurgeTransactionTests(unittest.TestCase):
    stamp=1700000000

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/"commands.db"
        self.patches=[patch.object(server,"DB_PATH",self.path),patch.object(server,"now",lambda:self.stamp),
            patch.object(server,"audit",lambda *args:None),patch.object(server,"signal_stream",lambda:None),
            patch.object(server,"push_tickle_async",lambda *args:None)]
        for item in self.patches:item.start()
        server.init_db()
        self.original={"commandId":"original","idempotencyKey":"original-intent","type":"sms.send",
            "createdAt":self.stamp,"expiresAt":self.stamp+3600,"ciphertext":api_fixture.ApiTest.cipher(2)}
        with server.open_db() as con:
            con.execute("INSERT INTO devices(id,token_hash,name,created_at) VALUES('node',?,'Node',?)",
                (server.sha256_text("D"*48),self.stamp))
        self.call("create",self.original)

    def tearDown(self):
        for item in reversed(self.patches):item.stop()
        self.temp.cleanup()

    def call(self, operation, body=None):
        responses=[]
        handler=SimpleNamespace(ip="127.0.0.1",headers={"Authorization":"Device "+"D"*48},
            send_json=lambda status,value:responses.append((status,value)),
            send_error_json=lambda status,code,message:responses.append((status,{"error":code})))
        if operation=="purge":server.SimHubHandler.purge_device_sms(handler,"node")
        elif operation=="poll":server.SimHubHandler.get_pending_commands(handler,"node",{})
        elif operation=="ack":server.SimHubHandler.ack_command(handler,"node","original",body)
        else:server.SimHubHandler.create_command(handler,"node",body)
        return responses[-1]

    def overlap(self, first, second, pause_sql, body=None):
        held=threading.Event();resume=threading.Event();second_started=threading.Event()
        real_open=server.open_db
        @contextmanager
        def connections():
            with real_open() as con:
                class Connection:
                    def execute(inner,sql,args=()):
                        result=con.execute(sql,args)
                        if threading.current_thread().name.endswith("_0") and pause_sql in sql:
                            held.set()
                            if not resume.wait(timeout=5):raise TimeoutError("Transaction was not released")
                        return result
                    def executemany(inner,sql,args):
                        result=con.executemany(sql,args)
                        if threading.current_thread().name.endswith("_0") and pause_sql in sql:
                            held.set()
                            if not resume.wait(timeout=5):raise TimeoutError("Transaction was not released")
                        return result
                yield Connection()
        def later():
            second_started.set()
            return self.call(second,body)
        with patch.object(server,"open_db",connections):
            with ThreadPoolExecutor(max_workers=2,thread_name_prefix="purge") as pool:
                before=pool.submit(self.call,first)
                try:
                    self.assertTrue(held.wait(timeout=5))
                    after=pool.submit(later)
                    self.assertTrue(second_started.wait(timeout=5))
                    self.assertFalse(after.done())
                finally:resume.set()
                return before.result(timeout=5),after.result(timeout=5)

    def test_purge_transaction_blocks_original_retry_until_tombstone_commits(self):
        _,retry=self.overlap("purge","create","INSERT INTO command_tombstones",self.original)
        self.assertEqual(retry[0],200)
        self.assertTrue(retry[1]["purged"])
        self.assertEqual(retry[1]["state"],"rejected")
        self.assertEqual(self.call("poll")[1]["commands"],[])

    def test_dispatch_and_purge_each_serialize_before_the_other(self):
        dispatch,_=self.overlap("poll","purge","UPDATE commands SET state='dispatched'")
        self.assertEqual(len(dispatch[1]["commands"]),1)
        with server.open_db() as con:
            self.assertEqual(con.execute("SELECT state FROM command_tombstones").fetchone()[0],"outcome_unknown")
            con.execute("DELETE FROM command_tombstones")
        self.call("create",self.original)
        _,dispatch=self.overlap("purge","poll","INSERT INTO command_tombstones")
        self.assertEqual(dispatch[1]["commands"],[])
        with server.open_db() as con:
            self.assertEqual(con.execute("SELECT state FROM command_tombstones").fetchone()[0],"rejected")

    def test_late_ack_waits_for_purge_then_resolves_metadata_only(self):
        self.call("poll")
        _,receipt=self.overlap("purge","ack","INSERT INTO command_tombstones",
            {"state":"sent","result":{"reason":"private receipt body"}})
        self.assertEqual(receipt[0],200)
        self.assertTrue(receipt[1]["purged"])
        self.assertEqual(receipt[1]["currentState"],"sent")
        with server.open_db() as con:
            self.assertEqual(con.execute("SELECT COUNT(*) FROM commands").fetchone()[0],0)

    def test_content_delete_failure_rolls_back_tombstone_and_purge_epoch(self):
        with server.open_db() as con:
            con.executescript("""CREATE TRIGGER fail_delete BEFORE DELETE ON commands
                BEGIN SELECT RAISE(ABORT,'injected disk failure'); END;""")
        with self.assertRaisesRegex(sqlite3.IntegrityError,"injected disk failure"):
            self.call("purge")
        with server.open_db() as con:
            self.assertEqual(con.execute("SELECT COUNT(*) FROM commands").fetchone()[0],1)
            self.assertEqual(con.execute("SELECT COUNT(*) FROM command_tombstones").fetchone()[0],0)
            self.assertEqual(con.execute("SELECT sms_epoch FROM devices").fetchone()[0],0)

    def test_retention_covers_original_expiry_and_safety_window_then_cleans(self):
        with patch.object(server,"COMMAND_RETENTION_DAYS",1):self.call("purge")
        with server.open_db() as con:
            deadline=con.execute("SELECT retain_until FROM command_tombstones").fetchone()[0]
        self.assertEqual(deadline,self.original["expiresAt"]+86400)
        with patch.object(server,"now",lambda:deadline-1):
            self.assertEqual(server.run_maintenance()["commandTombstones"],0)
            self.call("purge")  # Repeated deletion does not extend retained metadata.
        with server.open_db() as con:
            self.assertEqual(con.execute("SELECT retain_until FROM command_tombstones").fetchone()[0],deadline)
        with patch.object(server,"now",lambda:deadline):
            self.assertEqual(server.run_maintenance()["commandTombstones"],1)
            self.assertEqual(self.call("create",self.original)[0],400)

    def test_database_guard_blocks_older_binary_replay_and_device_delete_cleans_fence(self):
        self.call("purge")
        for cid,idem in (("original","changed"),("changed","original-intent")):
            with self.assertRaisesRegex(sqlite3.IntegrityError,"command_purged"):
                with server.open_db() as con:
                    con.execute("""INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json)
                        VALUES(?,'node','sms.send',?,?,?,'{}')""",(cid,self.stamp,self.stamp+3600,idem))
        with server.open_db() as con:
            # Older versions do not explicitly delete the additive table.
            con.execute("DELETE FROM devices WHERE id='node'")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM command_tombstones").fetchone()[0],0)


if __name__=="__main__":
    unittest.main()
