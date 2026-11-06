"""Durable Modem recovery using synthetic keys, messages and adapters only."""
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

import simhub_modem_agent as modem


class FakeAdapter:
    name="synthetic"
    def __init__(self, records=()): self.records=list(records)
    def fingerprint(self): return "synthetic-fingerprint"
    def list_sms(self): return self.records[:]
    def delete_sms(self, sms): self.records.remove(sms)


class FakeRelay:
    def __init__(self, failure=None): self.failure=failure;self.calls=[]
    def request(self, method, path, *args, **kwargs):
        self.calls.append((method,path))
        if self.failure: raise self.failure
        return {}


def make_agent(root: Path, name: str, device: str, key: bytes) -> modem.Agent:
    directory=root/name;directory.mkdir(mode=0o700)
    agent=modem.Agent.__new__(modem.Agent)
    agent.config_path=directory/"config.json";agent.db_path=directory/"agent.db"
    agent.node_key=key;agent.kid=modem.key_id(key)
    agent.config={"deviceId":device,"channelId":"old-channel","channelRevision":1,
                  "channelFingerprint":"synthetic-fingerprint","nodeKey":modem.b64u(key),
                  "keyId":agent.kid,"deviceToken":"synthetic-token-not-for-archive"}
    modem.atomic_write_json(agent.config_path,agent.config)
    agent.store=modem.Store(agent.db_path);agent.adapter=FakeAdapter();agent.relay=FakeRelay()
    return agent


class ModemRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.old=make_agent(self.root,"old","old-node",bytes(range(32)))

    def tearDown(self):
        try:self.old.store.db.close()
        except Exception:pass
        self.temp.cleanup()

    def sms(self, local_id="dji-at-synthetic", part=1, total=1):
        return modem.SmsRecord(local_id,"+10000000000","part-"+str(part),modem.now(),
                               concat_ref="8:1" if total>1 else "",concat_total=total,concat_seq=part)

    def stage_pending(self):
        self.old.adapter.records=[self.sms()]
        self.old.receive()
        self.assertEqual(len(self.old.adapter.records),0)
        self.assertEqual(len(self.old.store.pending_events()),1)
        part=self.sms("dji-at-part",1,2);group=self.old._multipart_group_id(part)
        self.old.store.queue_multipart_part(group,1,2,self.old._encrypt_multipart_part(group,part))
        self.old.store.mark_seen(part.local_id)
        self.old.store.claim_command("old-command")
        self.old.store.finish_command_and_queue_ack("old-command","submitted",{"reason":"synthetic"})

    def archive_reset(self):
        self.stage_pending()
        with self.assertRaises(modem.NodeResetComplete):self.old._finish_reset()
        archives=list((self.old.config_path.parent/"recovery").glob("recovery-*"))
        self.assertEqual(len(archives),1)
        return archives[0]

    def test_reset_retains_verified_private_snapshot_without_token(self):
        archive=self.archive_reset()
        self.assertFalse(self.old.config_path.exists());self.assertFalse(self.old.db_path.exists())
        self.assertEqual(os.stat(archive).st_mode & 0o777,0o700)
        for name in ("recovery.json","pending.db"):
            self.assertEqual(os.stat(archive/name).st_mode & 0o777,0o600)
        self.assertNotIn("synthetic-token-not-for-archive",(archive/"recovery.json").read_text())
        manifest,db=modem.open_recovery_archive(archive)
        try:self.assertEqual(manifest["counts"],{"events":1,"parts":1,"acks":1,"claims":0})
        finally:db.close()

    def test_restore_reencrypts_for_new_node_without_modifying_archive(self):
        archive=self.archive_reset();before=modem.file_sha256(archive/"pending.db")
        new=make_agent(self.root,"new","new-node",bytes(reversed(range(32))))
        try:
            result=new.import_recovery(archive)
            self.assertEqual(result,{"events":1,"parts":1,"retainedAcks":1,"retainedClaims":0})
            self.assertEqual(new.import_recovery(archive)["events"],0)
            self.assertEqual(new.import_recovery(archive)["parts"],0)
            event=new.store.pending_events()[0]
            aad=modem.event_aad("new-node",event["eventId"],event["kind"],event["occurredAt"],event["subscriptionId"],event["hasOtp"])
            payload=modem.decrypt_payload(new.node_key,new.kid,event["ciphertext"],aad)
            self.assertEqual(payload["body"],"part-1")
            self.assertEqual(payload["channelRevision"],1)
            self.assertEqual(modem.file_sha256(archive/"pending.db"),before)
        finally:new.store.db.close()

    def test_tampered_archive_cannot_be_imported(self):
        archive=self.archive_reset()
        with (archive/"pending.db").open("ab") as output:output.write(b"tampered")
        new=make_agent(self.root,"new","new-node",bytes(reversed(range(32))))
        try:
            with self.assertRaises(ValueError):new.import_recovery(archive)
            self.assertEqual(new.store.pending_events(),[])
        finally:new.store.db.close()

    def test_import_failure_rolls_back_all_staged_data(self):
        archive=self.archive_reset()
        new=make_agent(self.root,"new","new-node",bytes(reversed(range(32))))
        try:
            with patch.object(new,"_decrypt_multipart_with_key",side_effect=ValueError("synthetic failure")):
                with self.assertRaises(ValueError):new.import_recovery(archive)
            self.assertEqual(new.store.pending_events(),[])
            self.assertEqual(new.store.db.execute("SELECT COUNT(*) FROM recovery_imports").fetchone()[0],0)
            self.assertEqual(new.store.db.execute("SELECT COUNT(*) FROM seen_sms").fetchone()[0],0)
        finally:new.store.db.close()

    def test_interrupted_command_claim_alone_is_archived(self):
        self.assertTrue(self.old.store.claim_command("uncertain-command"))
        archive=self.old._preserve_pending()
        manifest,db=modem.open_recovery_archive(archive)
        try:
            self.assertEqual(manifest["counts"]["claims"],1)
            self.assertEqual(db.execute("SELECT state FROM processed_commands").fetchone()[0],"claimed")
        finally:db.close()

    def test_duplicate_command_restores_missing_ack_without_resending(self):
        self.old.store.claim_command("old-command")
        self.old.store.finish_command_and_queue_ack("old-command","sent",{})
        self.old.store.mark_ack_sent("old-command","sent")
        class DuplicateRelay:
            def request(self,*args,**kwargs):return {"commands":[{"commandId":"old-command"}]}
        self.old.relay=DuplicateRelay()
        self.old.process_commands()
        self.assertEqual(self.old.store.pending_acks()[0]["state"],"sent")

    def test_modem_send_stages_event_and_ack_without_duplicate_radio_send(self):
        sent=[]
        self.old.adapter.send_sms=lambda to,body:sent.append((to,body)) or {"messageRef":"synthetic"}
        created=modem.now();expires=created+300;command="synthetic-send"
        payload={"action":"sms.send","commandId":command,"channelId":"old-channel",
                 "channelRevision":1,"to":"+10000000000","body":"synthetic"}
        aad=modem.command_aad("old-node",command,"sms.send",created,expires,command)
        envelope={"commandId":command,"type":"sms.send","createdAt":created,"expiresAt":expires,
                  "idempotencyKey":command,"ciphertext":modem.encrypt_payload(self.old.node_key,self.old.kid,payload,aad)}
        class SendRelay:
            def request(self,*args,**kwargs):return {"commands":[envelope]}
        self.old.relay=SendRelay()
        self.old.process_commands();self.old.process_commands()
        self.assertEqual(len(sent),1)
        self.assertEqual(self.old.store.pending_acks()[0]["state"],"sent")
        self.assertEqual(self.old.store.pending_events()[0]["eventId"],"modem-send-"+command)

    def test_snapshot_failure_blocks_reset_and_keeps_originals(self):
        self.stage_pending()
        with patch.object(self.old,"_preserve_pending",side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):self.old._finish_reset()
        self.assertEqual(self.old.relay.calls,[])
        self.assertTrue(self.old.config_path.exists());self.assertTrue(self.old.db_path.exists())

    def test_offline_reset_reuses_existing_archive(self):
        self.stage_pending();self.old.relay=FakeRelay(urllib.error.URLError("offline"))
        for _ in range(2):
            with self.assertRaises(urllib.error.URLError):self.old._finish_reset()
        self.assertEqual(len(list((self.old.config_path.parent/"recovery").glob("recovery-*"))),1)
        self.assertTrue(self.old.config_path.exists())

    def test_remote_deleted_node_also_preserves_pending(self):
        self.stage_pending();self.old.relay=FakeRelay(RuntimeError("relay HTTP 410: removed"))
        with self.assertRaises(modem.NodeResetComplete):self.old._check_reset()
        self.assertEqual(len(list((self.old.config_path.parent/"recovery").glob("recovery-*"))),1)

    def test_offline_receives_locally_but_auth_failure_does_not(self):
        self.old.adapter.records=[self.sms()];self.old.relay=FakeRelay(urllib.error.URLError("offline"))
        with self.assertRaises(urllib.error.URLError):self.old.cycle()
        self.assertEqual(len(self.old.store.pending_events()),1)
        self.old.adapter.records=[self.sms("dji-at-second")]
        self.old.relay=FakeRelay(RuntimeError("relay HTTP 401: unauthorized"))
        with self.assertRaises(RuntimeError):self.old.cycle()
        self.assertEqual(len(self.old.adapter.records),1)

    def test_lost_commit_response_recovers_before_lifecycle_even_after_expiry(self):
        old_token=self.old.config["deviceToken"]
        pending="new-synthetic-device-token-"+"D"*32
        self.old.config["tokenIssuedAt"]=modem.now()-modem.TOKEN_ROTATE_AFTER-1
        modem.atomic_write_json(self.old.config_path,self.old.config)
        class LostCommitRelay:
            device_token=old_token
            active=old_token
            lost=False
            calls=[]
            def request(relay,method,path,body=None,token_override=None):
                token=token_override or relay.device_token
                relay.calls.append((path,token))
                if path.endswith("/token/prepare"):
                    return {"deviceToken":pending,"expiresAt":modem.now()+3600}
                if path.endswith("/token/commit"):
                    self.assertEqual(token,pending)
                    relay.active=pending
                    if not relay.lost:
                        relay.lost=True
                        raise OSError("response lost after durable commit")
                    return {"ok":True,"alreadyCommitted":True,"tokenIssuedAt":modem.now()}
                if token!=relay.active:
                    raise RuntimeError("relay HTTP 401: old credential")
                return {"resetRequired":False,"status":"active"} if path.endswith("/lifecycle") else {"commands":[]}
        self.old.relay=LostCommitRelay()
        with self.assertRaises(OSError):self.old.rotate_device_token()
        self.assertEqual(self.old.config["deviceToken"],old_token)
        self.assertEqual(modem.load_json(self.old.config_path)["pendingDeviceToken"],pending)
        # Model a restart occurring after the local prepare deadline.
        self.old.config=modem.load_json(self.old.config_path)
        self.old.config["pendingTokenExpiresAt"]=modem.now()-1
        modem.atomic_write_json(self.old.config_path,self.old.config)
        self.old.relay.calls=[]
        with patch.object(self.old,"put_state"),patch.object(self.old,"heartbeat"):
            self.old.cycle()
        self.assertTrue(self.old.relay.calls[0][0].endswith("/token/commit"))
        self.assertTrue(self.old.relay.calls[1][0].endswith("/lifecycle"))
        self.assertTrue(all(token==pending for _,token in self.old.relay.calls))
        self.assertEqual(modem.load_json(self.old.config_path)["deviceToken"],pending)
        self.assertNotIn("pendingDeviceToken",self.old.config)

    def test_pending_candidate_discard_requires_definitive_old_credential_proof(self):
        pending="pending-synthetic-token-"+"P"*32
        old_token=self.old.config["deviceToken"]
        class RejectPending:
            device_token=old_token
            old_failure=None
            candidate_failure=RuntimeError("relay HTTP 401: rejected pending")
            def request(relay,method,path,body=None,token_override=None):
                if path.endswith("/token/commit"):
                    raise RuntimeError("relay HTTP 401: rejected pending")
                if token_override==pending:
                    raise relay.candidate_failure
                if relay.old_failure:raise relay.old_failure
                return {"resetRequired":False,"status":"active"}
        relay=RejectPending();self.old.relay=relay
        def journal():
            self.old.config.update({"pendingDeviceToken":pending,"pendingTokenExpiresAt":1,"tokenRotationPending":True})
            modem.atomic_write_json(self.old.config_path,self.old.config)
        for failure in (RuntimeError("relay HTTP 401: invalid old"),urllib.error.URLError("offline")):
            journal();relay.old_failure=failure
            with self.assertRaises((RuntimeError,OSError)):self.old.recover_pending_device_token()
            self.assertEqual(modem.load_json(self.old.config_path)["pendingDeviceToken"],pending)
        relay.old_failure=None
        relay.candidate_failure=RuntimeError("relay HTTP 404: lifecycle unavailable")
        with self.assertRaises(RuntimeError):self.old.recover_pending_device_token()
        self.assertEqual(self.old.config["pendingDeviceToken"],pending)
        relay.candidate_failure=RuntimeError("relay HTTP 401: rejected pending")
        self.assertFalse(self.old.recover_pending_device_token())
        self.assertNotIn("pendingDeviceToken",modem.load_json(self.old.config_path))
        self.assertEqual(self.old.config["deviceToken"],old_token)

    def test_revoked_pending_current_token_is_used_only_for_cleanup(self):
        pending="revoked-current-token-"+"R"*32
        self.old.config.update({"pendingDeviceToken":pending,"pendingTokenExpiresAt":1})
        modem.atomic_write_json(self.old.config_path,self.old.config)
        class RevokedRelay:
            device_token=self.old.config["deviceToken"]
            calls=[]
            def request(relay,method,path,body=None,token_override=None):
                token=token_override or relay.device_token
                relay.calls.append((path,token))
                if path.endswith("/token/commit"):
                    raise RuntimeError("relay HTTP 409: only reset acknowledgement allowed")
                self.assertEqual(token,pending)
                if path.endswith("/lifecycle"):
                    return {"resetRequired":True,"status":"revoked"}
                if path.endswith("/reset"):
                    return {"ok":True,"status":"revoked"}
                self.fail("Revoked token reached normal business API")
        relay=RevokedRelay();self.old.relay=relay
        with self.assertRaises(modem.NodeResetComplete):self.old.cycle()
        self.assertFalse(self.old.config_path.exists())
        self.assertEqual(len(relay.calls),3)

    def test_token_journal_survives_local_write_failure_after_commit(self):
        pending="new-current-token-"+"N"*32
        old_token=self.old.config["deviceToken"]
        self.old.config.update({"pendingDeviceToken":pending,"pendingTokenExpiresAt":modem.now()+300})
        modem.atomic_write_json(self.old.config_path,self.old.config)
        class CommittedRelay:
            device_token=old_token
            def request(relay,*args,**kwargs):return {"ok":True,"tokenIssuedAt":modem.now()}
        self.old.relay=CommittedRelay()
        with patch.object(modem,"atomic_write_json",side_effect=OSError("disk full")):
            with self.assertRaises(OSError):self.old.recover_pending_device_token()
        self.assertEqual(self.old.config["deviceToken"],old_token)
        self.assertEqual(self.old.relay.device_token,old_token)
        self.assertEqual(modem.load_json(self.old.config_path)["pendingDeviceToken"],pending)
        self.assertTrue(self.old.recover_pending_device_token())
        self.assertEqual(modem.load_json(self.old.config_path)["deviceToken"],pending)

    def test_failed_hardware_deletion_is_retried_without_duplicate_event(self):
        self.old.adapter.records=[self.sms()]
        real_delete=self.old.adapter.delete_sms
        with patch.object(self.old.adapter,"delete_sms",side_effect=OSError("synthetic failure")):
            self.old.receive()
        self.assertEqual(len(self.old.adapter.records),1)
        with patch.object(self.old.adapter,"delete_sms",side_effect=real_delete):self.old.receive()
        self.assertEqual(self.old.adapter.records,[])
        self.assertEqual(len(self.old.store.pending_events()),1)

    def test_insecure_recovery_directory_blocks_reset(self):
        self.stage_pending()
        root=self.old.config_path.parent/"recovery";root.mkdir(mode=0o755);root.chmod(0o755)
        with self.assertRaises(PermissionError):self.old._finish_reset()
        self.assertEqual(self.old.relay.calls,[])
        self.assertTrue(self.old.db_path.exists())

    def test_archived_event_and_its_already_completed_parts_import_only_once(self):
        first=self.sms("dji-at-one",1,2);second=self.sms("dji-at-two",2,2)
        group=self.old._multipart_group_id(first)
        for sms in (first,second):
            self.old.store.queue_multipart_part(group,sms.concat_seq,sms.concat_total,self.old._encrypt_multipart_part(group,sms))
        self.assertTrue(self.old._emit_multipart_group(group))
        # Simulate a crash between staging the complete event and deleting parts.
        for sms in (first,second):
            self.old.store.queue_multipart_part(group,sms.concat_seq,sms.concat_total,self.old._encrypt_multipart_part(group,sms))
        archive=self.old._preserve_pending()
        new=make_agent(self.root,"new","new-node",bytes(reversed(range(32))))
        try:
            imported=new.import_recovery(archive)
            self.assertEqual(imported["events"],1);self.assertEqual(imported["parts"],0)
            self.assertEqual(new.store.db.execute("SELECT COUNT(*) FROM multipart_parts").fetchone()[0],0)
        finally:new.store.db.close()

    def test_complete_fragments_resume_and_keep_original_sim_revision(self):
        first=self.sms("dji-at-one",1,2);second=self.sms("dji-at-two",2,2)
        group=self.old._multipart_group_id(first)
        for sms in (first,second):
            self.old.store.queue_multipart_part(group,sms.concat_seq,sms.concat_total,self.old._encrypt_multipart_part(group,sms))
            self.old.store.mark_seen(sms.local_id)
        self.old.config["channelRevision"]=2
        self.old.receive()
        self.assertEqual(self.old.store.multipart_group(group),[])
        event=self.old.store.pending_events()[0]
        aad=modem.event_aad("old-node",event["eventId"],event["kind"],event["occurredAt"],event["subscriptionId"],event["hasOtp"])
        payload=modem.decrypt_payload(self.old.node_key,self.old.kid,event["ciphertext"],aad)
        self.assertEqual(payload["channelRevision"],1)
        self.assertEqual(payload["body"],"part-1part-2")

    def test_legacy_fragments_never_infer_current_sim(self):
        group="legacy-group"
        for part in (1,2):
            payload={"sender":"+10000000000","body":"x","occurredAt":modem.now(),"localId":"legacy-"+str(part)}
            self.old.store.queue_multipart_part(group,part,2,self.old._encrypt_multipart_payload(group,part,2,payload))
        self.old.receive();event=self.old.store.pending_events()[0]
        aad=modem.event_aad("old-node",event["eventId"],event["kind"],event["occurredAt"],event["subscriptionId"],event["hasOtp"])
        payload=modem.decrypt_payload(self.old.node_key,self.old.kid,event["ciphertext"],aad)
        self.assertEqual(payload["channelId"],"");self.assertEqual(payload["channelRevision"],0)


if __name__ == "__main__":
    unittest.main()
