"""Exercise the controller pairing protocol through HTTP and the existing auth gate."""
import hashlib
import base64
import json
import sqlite3
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT=Path(__file__).resolve().parents[1]
ADMIN_TOKEN="A"*48

def b64(x):
    return base64.urlsafe_b64encode(x).decode().rstrip("=")

def pub(pair):
    return b64(pair.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo))

class ControllerHttpTest(unittest.TestCase):
    def setUp(self):
        # Authentication failure budgets intentionally survive successful
        # requests. Keep independent negative scenarios on independent Relays.
        self.work=tempfile.TemporaryDirectory()
        with socket.socket() as s:
            s.bind(("127.0.0.1",0));self.port=s.getsockname()[1]
        self.url=f"http://127.0.0.1:{self.port}"
        env=os.environ.copy()
        env.update(SIMHUB_ADMIN_TOKEN=ADMIN_TOKEN,SIMHUB_BIND="127.0.0.1",
                   SIMHUB_PORT=str(self.port),SIMHUB_DB=str(Path(self.work.name)/"relay.db"),
                   SIMHUB_REQUIRE_TOTP="false")
        self.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],
            env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(self.url+"/readyz",timeout=.2).read()
                return
            except Exception: time.sleep(.05)
        raise RuntimeError("Controller HTTP relay did not start")

    def tearDown(self):
        self.proc.terminate()
        self.proc.wait(timeout=5)
        self.work.cleanup()

    def call(self,method,path,obj=None,cookie=None,admin=False,csrf=None,poll_token=None,device_token=None):
        headers={"Content-Type":"application/json"}
        if device_token:headers["Authorization"]="Device "+device_token
        if admin:headers["Authorization"]="Bearer "+ADMIN_TOKEN
        if cookie:headers["Cookie"]=cookie
        if csrf:headers["X-SimHub-CSRF"]=csrf
        if poll_token:headers["X-SimHub-Pair-Token"]=poll_token
        data=json.dumps(obj).encode() if obj is not None else None
        req=urllib.request.Request(self.url+path,method=method,data=data,headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=4) as response:
                return response.status,json.loads(response.read()),dict(response.headers)
        except urllib.error.HTTPError as exc:
            return exc.code,json.loads(exc.read()),dict(exc.headers)

    def test_optional_peer_response_requires_both_carrier_auth_and_opaque_schema(self):
        db=str(Path(self.work.name)/"relay.db");token="R"*48;device="peer-response-node";command="pw-"+"x"*43;ts=int(time.time())
        with sqlite3.connect(db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",(device,hashlib.sha256(token.encode()).hexdigest(),"Peer node",ts))
            con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json) VALUES(?,?,?,?,?,?,?)",(command,device,"peer.rpc",ts,ts+120,command,"{}"))
        path=f"/api/v1/devices/{device}/commands/{command}/peer-response"
        frame={"v":1,"domain":"simhub-peer-v1","from":"sandbox-node-identity000","to":"sandbox-controller-identity000","id":"sandbox-request-1234567890","expires":ts+60,"iv":b64(os.urandom(12)),"ct":b64(os.urandom(32))}
        self.assertEqual(self.call("POST",path,{"frame":frame})[0],401)
        self.assertEqual(self.call("POST",path,{"frame":frame},admin=True)[0],401)
        self.assertEqual(self.call("POST",path,{"frame":{**frame,"body":"must never enter metadata"}},device_token=token)[0],400)
        self.assertEqual(self.call("POST",path,{"frame":frame},device_token=token)[0],200)
        self.assertEqual(self.call("GET",path)[0],401)
        self.assertEqual(self.call("GET",path,device_token=token)[0],401)
        status,body,_=self.call("GET",path,admin=True);self.assertEqual(status,200);self.assertEqual(body["frame"],frame)
        replacement={**frame,"ct":b64(os.urandom(32))}
        self.assertEqual(self.call("POST",path,{"frame":replacement},device_token=token)[0],200)
        self.assertEqual(self.call("GET",path,admin=True)[1]["frame"],frame)
        status,body,_=self.call("GET","/api/v1/commands/recent",admin=True);self.assertEqual(status,200);self.assertNotIn(frame["ct"],json.dumps(body))
        with sqlite3.connect(db) as con:con.execute("UPDATE devices SET revoked_at=? WHERE id=?",(ts,device))
        self.assertEqual(self.call("GET",path,admin=True)[0],404)
        self.assertEqual(self.call("POST",path,{"frame":frame},device_token=token)[0],401)

    def test_native_device_management_post_requires_admin_and_validates_actions(self):
        db=str(Path(self.work.name)/"relay.db")
        with sqlite3.connect(db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        ("native-manage-test","native-manage-token","Original label",1760000000))
        path="/api/v1/devices/native-manage-test/manage"
        status,_,_=self.call("POST",path,{"action":"rename","name":"New name"})
        self.assertEqual(status,401)
        status,_,_=self.call("POST",path,{"action":"unsupported"},admin=True)
        self.assertEqual(status,400)
        status,_,_=self.call("POST",path,{"action":"rename","name":""},admin=True)
        self.assertEqual(status,400)
        status,body,_=self.call("POST",path,{"action":"rename","name":"工作设备"},admin=True)
        self.assertEqual((status,body.get("ok")),(200,True))
        with sqlite3.connect(db) as con:
            name=con.execute("SELECT name FROM devices WHERE id=?",
                             ("native-manage-test",)).fetchone()[0]
        self.assertEqual(name,"工作设备")

    def test_two_thousand_event_delta_cursor_and_historical_pages(self):
        # Encryptions are opaque to Relay; paging must preserve *all* sequence
        # IDs across bounded batches before the Android client ACKs progress.
        db=str(Path(self.work.name)/"relay.db")
        with sqlite3.connect(db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        ("bulk-test-node","bulk-test-token","Fixture node",1760000000))
            con.executemany(
                """INSERT OR IGNORE INTO events(id,device_id,kind,occurred_at,received_at,ciphertext_json)
                   VALUES(?,?,?,?,?,?)""",
                [(f"bulk-{i}","bulk-test-node","sms.received",1760000000+i,
                  1760000000+i,"{}") for i in range(2050)])
        cursor=0
        observed=[]
        for _ in range(100):
            status,page,_=self.call("GET",f"/api/v1/events?since={cursor}&limit=50",admin=True)
            self.assertEqual(status,200)
            events=page["events"]
            if not events:break
            seqs=[e["seq"] for e in events]
            self.assertTrue(all(a<b for a,b in zip(seqs,seqs[1:])))
            self.assertGreater(seqs[0],cursor)
            observed.extend(e["eventId"] for e in events if e["deviceId"]=="bulk-test-node")
            # This update models an ACK *after* accepting a complete page.
            cursor=seqs[-1]
            if len(events)<50:break
        self.assertEqual(observed,[f"bulk-{i}" for i in range(2050)])
        before_time=before_seq=0
        history=[]
        for _ in range(60):
            suffix=f"&beforeTime={before_time}&beforeSeq={before_seq}" if before_time else ""
            status,page,_=self.call("GET","/api/v1/events?order=occurred&limit=50"+suffix,admin=True)
            self.assertEqual(status,200)
            history.extend(e["eventId"] for e in page["events"] if e["deviceId"]=="bulk-test-node")
            if not page["hasMore"]:break
            before_time=page["nextBeforeTime"]
            before_seq=page["nextBeforeSeq"]
        self.assertEqual(history,[f"bulk-{i}" for i in reversed(range(2050))])

    def test_outbox_summary_is_authenticated_and_metadata_only(self):
        self.assertEqual(self.call("GET","/api/v1/notifications/outbox")[0],401)
        status,body,_=self.call("GET","/api/v1/notifications/outbox",admin=True)
        self.assertEqual(status,200)
        self.assertEqual(set(body["counts"]),{"pending","retry","inflight","delivered","failed","expired"})
        self.assertNotIn("payload",json.dumps(body))
        self.assertNotIn("token",json.dumps(body).lower())

    def test_parallel_key_rotations_cannot_replace_another_envelope(self):
        from concurrent.futures import ThreadPoolExecutor
        db=str(Path(self.work.name)/"relay.db")
        device="parallel-rotation-node"
        with sqlite3.connect(db) as con:
            con.execute("INSERT INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        (device,"synthetic-token","Key rotation test",int(time.time())))
        path="/api/v1/devices/"+device
        envelopes=[
            {"v":1,"alg":"A256GCM","iv":"0123456789abcdef",
             "ct":"a"*40},
            {"v":1,"alg":"A256GCM","iv":"fedcba9876543210",
             "ct":"b"*40}
        ]
        requests=[{"pendingKeyId":"rotation-key-a","pendingWrappedKey":envelopes[0]},
                  {"pendingKeyId":"rotation-key-b","pendingWrappedKey":envelopes[1]}]
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes=list(pool.map(lambda request:self.call("PATCH",path,request,admin=True)[0],requests))
        self.assertEqual(sorted(outcomes),[200,409])
        with sqlite3.connect(db) as con:
            row=con.execute("SELECT pending_key_id,pending_wrapped_key_json FROM devices WHERE id=?",
                            (device,)).fetchone()
        winner=0 if row[0]=="rotation-key-a" else 1
        self.assertEqual(json.loads(row[1]),envelopes[winner])
        self.assertEqual(self.call("PATCH",path,requests[winner],admin=True)[0],200)
        altered={"pendingKeyId":requests[winner]["pendingKeyId"],
                 "pendingWrappedKey":envelopes[1-winner]}
        self.assertEqual(self.call("PATCH",path,altered,admin=True)[0],409)

    def test_first_use_approval_session_and_server_revocation(self):
        identity=ec.generate_private_key(ec.SECP256R1())
        exchange=ec.generate_private_key(ec.SECP256R1())
        s,pair,_=self.call("POST","/api/v1/controllers/pair",
            {"label":"Test Pixel","identityPublic":pub(identity),"exchangePublic":pub(exchange),
             "pairingProtocolVersion":2})
        self.assertEqual(s,201)
        pair_id=pair["requestId"]
        s,pending,_=self.call("GET","/api/v1/controllers/pending",admin=True)
        self.assertEqual(s,200)
        self.assertTrue(any(p["requestId"]==pair_id for p in pending["pending"]))
        s,_,_=self.call("POST","/api/v1/controllers/approve",
            {"requestId":pair_id,"pairingCode":"000000"},admin=False)
        self.assertEqual(s,401)
        envelope={"v":1,"epk":pub(ec.generate_private_key(ec.SECP256R1())),
                  "salt":b64(bytes(16)),"iv":b64(bytes(12)),"ct":b64(bytes(48))}
        s,grant,_=self.call("POST","/api/v1/controllers/approve",
            {"requestId":pair_id,"pairingCode":pair["pairingCode"],"vaultEnvelope":envelope},
            admin=True)
        self.assertEqual(s,200)
        self.assertEqual(grant["ok"],True)
        cid=grant["controllerId"]
        s,poll,_=self.call("GET","/api/v1/controllers/pair/"+pair_id,poll_token=pair["pollToken"])
        self.assertEqual(s,200)
        self.assertEqual(poll["vaultEnvelope"],envelope)
        s,response,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(s,200)
        nonce=response["challenge"]
        signature=identity.sign(f"simhub-controller-login-v1|{cid}|{nonce}".encode(),
                                ec.ECDSA(hashes.SHA256()))
        body={"controllerId":cid,"challenge":nonce,"signature":b64(signature)}
        s,login,headers=self.call("POST","/api/v1/controllers/login/verify",body)
        self.assertEqual(s,201)
        cookie=headers["Set-Cookie"].split(";",1)[0]
        s,_,_=self.call("GET","/api/v1/devices",cookie=cookie)
        self.assertEqual(s,200)
        s,_,_=self.call("POST","/api/v1/controllers/login/verify",body)
        self.assertEqual(s,401)
        s,_,_=self.call("POST","/api/v1/controllers/revoke",{"controllerId":cid},admin=True)
        self.assertEqual(s,200)
        s,_,_=self.call("GET","/api/v1/devices",cookie=cookie)
        self.assertEqual(s,401)
        s,_,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(s,401)

    def test_rejected_signature_consumes_challenge_and_revoked_session_cannot_reappear(self):
        identity=ec.generate_private_key(ec.SECP256R1())
        exchange=ec.generate_private_key(ec.SECP256R1())
        _,pair,_=self.call("POST","/api/v1/controllers/pair",{
            "label":"Nonce regression","identityPublic":pub(identity),
            "exchangePublic":pub(exchange),"pairingProtocolVersion":2})
        envelope={"v":1,"epk":pub(ec.generate_private_key(ec.SECP256R1())),
                  "salt":b64(bytes(16)),"iv":b64(bytes(12)),"ct":b64(bytes(48))}
        status,grant,_=self.call("POST","/api/v1/controllers/approve",{
            "requestId":pair["requestId"],"pairingCode":pair["pairingCode"],
            "vaultEnvelope":envelope},admin=True)
        self.assertEqual(status,200)
        cid=grant["controllerId"]
        status,challenge,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(status,200)
        nonce=challenge["challenge"]
        message=f"simhub-controller-login-v1|{cid}|{nonce}".encode()
        other=ec.generate_private_key(ec.SECP256R1())
        bad=other.sign(message,ec.ECDSA(hashes.SHA256()))
        good=identity.sign(message,ec.ECDSA(hashes.SHA256()))
        bad_request={"controllerId":cid,"challenge":nonce,"signature":b64(bad)}
        self.assertEqual(self.call("POST","/api/v1/controllers/login/verify",bad_request)[0],401)
        # A rollback must not resurrect a failed one-time challenge.
        self.assertEqual(self.call("POST","/api/v1/controllers/login/verify",{
            **bad_request,"signature":b64(good)})[0],401)
        status,challenge,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(status,200)
        nonce=challenge["challenge"]
        good=identity.sign(f"simhub-controller-login-v1|{cid}|{nonce}".encode(),
                           ec.ECDSA(hashes.SHA256()))
        status,_,headers=self.call("POST","/api/v1/controllers/login/verify",{
            "controllerId":cid,"challenge":nonce,"signature":b64(good)})
        self.assertEqual(status,201)
        cookie=headers["Set-Cookie"].split(";",1)[0]
        self.assertEqual(self.call("POST","/api/v1/controllers/revoke",{
            "controllerId":cid},admin=True)[0],200)
        # A session row artificially restored after revocation must still
        # fail the live-grant check even when its cookie has not expired.
        db=str(Path(self.work.name)/"relay.db")
        with sqlite3.connect(db) as con:
            fingerprint=con.execute("SELECT admin_fingerprint FROM controllers WHERE id=?",
                                    (cid,)).fetchone()[0]
            ts=int(time.time())
            con.execute("""INSERT INTO admin_sessions
                (token_hash,created_at,expires_at,last_seen_at,ip,admin_fingerprint,controller_id)
                VALUES(?,?,?,?,?,?,?)""",(
                hashlib.sha256(cookie.split("=",1)[1].encode()).hexdigest(),
                ts,ts+3600,ts,"127.0.0.1",fingerprint,cid))
        self.assertEqual(self.call("GET","/api/v1/devices",cookie=cookie)[0],401)

if __name__=="__main__":
    unittest.main()
