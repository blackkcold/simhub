#!/usr/bin/env python3
import json, os, socket, sqlite3, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SERVER=ROOT/'server'/'simhub_server.py'
TOKEN='A'*48

def free_port():
    s=socket.socket();s.bind(('127.0.0.1',0));p=s.getsockname()[1];s.close();return p

class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.port=free_port();cls.base=f'http://127.0.0.1:{cls.port}';cls.db=Path(cls.tmp.name)/'test.db'
        env=os.environ.copy();env.update({'SIMHUB_ADMIN_TOKEN':TOKEN,'SIMHUB_BIND':'127.0.0.1','SIMHUB_PORT':str(cls.port),'SIMHUB_DB':str(cls.db),'SIMHUB_WEB_ROOT':str(ROOT/'web'),'SIMHUB_SESSION_TTL':'900','SIMHUB_REQUIRE_TOTP':'false'})
        cls.proc=subprocess.Popen([sys.executable,str(SERVER)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        for _ in range(80):
            try: urllib.request.urlopen(cls.base+'/readyz',timeout=.2).read();break
            except Exception: time.sleep(.05)
        else: raise RuntimeError('server failed to start')
    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate();cls.proc.wait(timeout=3);cls.tmp.cleanup()

    def req(self,method,path,body=None,device_token=None,admin=True,headers=None):
        data=None if body is None else json.dumps(body).encode()
        h={'Content-Type':'application/json'}
        if headers:h.update(headers)
        if device_token:h['Authorization']='Device '+device_token
        elif admin and 'Authorization' not in h:h['Authorization']='Bearer '+TOKEN
        req=urllib.request.Request(self.base+path,data=data,headers=h,method=method)
        try:
            with urllib.request.urlopen(req,timeout=3) as r:
                raw=r.read();return r.status,(json.loads(raw) if raw else {}),dict(r.headers)
        except urllib.error.HTTPError as e:
            raw=e.read();return e.code,(json.loads(raw) if raw else {}),dict(e.headers)

    def enroll(self,name='Pixel SIM Node'):
        st,x,_=self.req('POST','/api/v1/enrollments',{});self.assertEqual(st,201)
        st,d,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'name':name,'model':'Pixel'},admin=False);self.assertEqual(st,201)
        return d

    @staticmethod
    def cipher(v=1):
        x={'v':v,'alg':'A256GCM','iv':'AAAAAAAAAAAAAAAA','ct':'A'*32}
        if v==2:x['kid']='abcdefgh1234'
        return x

    def test_end_to_end(self):
        d=self.enroll();did=d['deviceId'];dt=d['deviceToken']
        ev={'eventId':'evt-1-'+did,'kind':'sms.received','occurredAt':int(time.time()),'subscriptionId':'1','hasOtp':True,'metadata':{'sender':'must-strip','parts':1},'ciphertext':self.cipher()}
        st,r,_=self.req('POST',f'/api/v1/devices/{did}/events',ev,device_token=dt,admin=False);self.assertEqual(st,201)
        st,r2,_=self.req('POST',f'/api/v1/devices/{did}/events',ev,device_token=dt,admin=False);self.assertEqual(st,200);self.assertTrue(r2['duplicate'])
        st,e,_=self.req('GET','/api/v1/events?since=0');self.assertEqual(st,200)
        found=[x for x in e['events'] if x['eventId']==ev['eventId']][0];self.assertNotIn('sender',found['metadata']);self.assertEqual(found['metadata'].get('parts'),1)
        cmd={'commandId':'cmd-1-'+did,'idempotencyKey':'idem-1','type':'sms.send','createdAt':int(time.time()),'expiresAt':int(time.time())+60,'ciphertext':self.cipher(2)}
        st,c,_=self.req('POST',f'/api/v1/devices/{did}/commands',cmd);self.assertEqual(st,201)
        st,p,_=self.req('GET',f'/api/v1/devices/{did}/commands/pending',device_token=dt,admin=False);self.assertEqual(st,200);self.assertEqual(p['commands'][0]['commandId'],cmd['commandId'])
        st,_,_=self.req('POST',f"/api/v1/devices/{did}/commands/{cmd['commandId']}/ack",{'state':'submitted','result':{'recipient':'must-strip','parts':1}},device_token=dt,admin=False);self.assertEqual(st,200)

    def test_multidevice_event_ids_are_scoped(self):
        a=self.enroll('A');b=self.enroll('B')
        for d in (a,b):
            ev={'eventId':'sms-provider-42','kind':'sms.received','occurredAt':int(time.time()),'subscriptionId':'1','hasOtp':False,'metadata':{'parts':1},'ciphertext':self.cipher(2)}
            st,_,_=self.req('POST',f"/api/v1/devices/{d['deviceId']}/events",ev,device_token=d['deviceToken'],admin=False);self.assertEqual(st,201)
        st,e,_=self.req('GET','/api/v1/events?since=0&limit=1000');self.assertEqual(st,200)
        matches=[x for x in e['events'] if x['eventId']=='sms-provider-42'];self.assertEqual(len(matches),2);self.assertNotEqual(matches[0]['deviceId'],matches[1]['deviceId'])

    def test_old_event_time_is_preserved(self):
        d=self.enroll('Archive');old=1577836800
        ev={'eventId':'old-'+d['deviceId'],'kind':'sms.history','occurredAt':old,'subscriptionId':'1','hasOtp':False,'metadata':{'history':True},'ciphertext':self.cipher(2)}
        st,_,_=self.req('POST',f"/api/v1/devices/{d['deviceId']}/events",ev,device_token=d['deviceToken'],admin=False);self.assertEqual(st,201)
        st,e,_=self.req('GET','/api/v1/events?since=0&limit=1000');row=[x for x in e['events'] if x['eventId']==ev['eventId']][0];self.assertEqual(row['occurredAt'],old)

    def test_admin_session_cookie(self):
        st,data,h=self.req('POST','/api/v1/auth/session',{'adminToken':TOKEN,'totp':''},admin=False);self.assertEqual(st,201);self.assertTrue(data['ok'])
        cookie=h.get('Set-Cookie','').split(';',1)[0];self.assertIn('simhub_session=',cookie)
        st,data,_=self.req('GET','/api/v1/auth/check',admin=False,headers={'Cookie':cookie});self.assertEqual(st,200);self.assertTrue(data['ok'])

    def test_state_allowlist_and_channel_projection(self):
        d=self.enroll('State')
        state={'androidVersion':'17','sdk':37,'nodeType':'android','cryptoKeyId':'active-key-1','queueFailures':2,'body':'must-drop','nested':{'otp':'must-drop'},'capabilities':['sms.receive','sms.send'],'subscriptions':[{'subscriptionId':7,'channelId':'channel-a','channelRevision':2,'slotIndex':0,'carrierName':'Carrier','displayName':'SIM A','signalRsrp':-95,'secret':'drop'}],'channels':[{'id':'channel-a','localId':'7','kind':'android-sim','revision':2,'slotIndex':0,'carrierName':'Carrier','displayName':'SIM A','signalRsrp':-95,'secret':'drop'}]}
        st,_,_=self.req('POST',f"/api/v1/devices/{d['deviceId']}/state",state,device_token=d['deviceToken'],admin=False);self.assertEqual(st,200)
        st,out,_=self.req('GET',f"/api/v1/devices/{d['deviceId']}/state");self.assertEqual(st,200);self.assertNotIn('body',out['state']);self.assertNotIn('nested',out['state']);self.assertNotIn('secret',out['state']['subscriptions'][0]);self.assertEqual(out['state']['channels'][0]['id'],'channel-a')
        with sqlite3.connect(self.db) as con:
            n=con.execute('SELECT COUNT(*) FROM subscriptions WHERE device_id=?',(d['deviceId'],)).fetchone()[0]
            ch=con.execute('SELECT id,revision FROM channels WHERE device_id=?',(d['deviceId'],)).fetchone()
        self.assertEqual(n,1);self.assertEqual(ch[0],'channel-a');self.assertEqual(ch[1],2)

    def test_generic_modem_enrollment_and_key_promotion(self):
        wrapped=self.cipher(1);kid='abcdefgh1234'
        bootstrap=self.cipher(1)
        proof='P'*43
        st,x,_=self.req('POST','/api/v1/enrollments',{'nodeType':'modem','capabilities':['sms.receive','sms.send','signal.radio'],'keyId':kid,'wrappedKey':wrapped,'bootstrapEnvelope':bootstrap,'bootstrapHash':proof});self.assertEqual(st,201)
        st,d,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'bootstrapProof':proof,'name':'DJI Node','model':'QDC507','nodeType':'modem'},admin=False);self.assertEqual(st,201)
        st,devices,_=self.req('GET','/api/v1/devices');row=[v for v in devices['devices'] if v['id']==d['deviceId']][0]
        self.assertEqual(row['nodeType'],'modem');self.assertEqual(row['keyId'],kid);self.assertIn('sms.send',row['capabilities'])

        legacy=self.enroll('Legacy')
        pending='ijklmnop5678'
        st,_,_=self.req('PATCH',f"/api/v1/devices/{legacy['deviceId']}",{'pendingKeyId':pending,'pendingWrappedKey':wrapped});self.assertEqual(st,200)
        state={'nodeType':'android','cryptoKeyId':pending,'capabilities':['sms.receive'],'channels':[],'subscriptions':[]}
        st,_,_=self.req('POST',f"/api/v1/devices/{legacy['deviceId']}/state",state,device_token=legacy['deviceToken'],admin=False);self.assertEqual(st,200)
        st,devices,_=self.req('GET','/api/v1/devices');row=[v for v in devices['devices'] if v['id']==legacy['deviceId']][0]
        self.assertEqual(row['keyId'],pending);self.assertIsNone(row['pendingKeyId'])

    def test_trusted_proxy_client_ip_is_used_for_audit(self):
        ip='203.0.113.42'
        st,_,_=self.req('POST','/api/v1/auth/session',{'adminToken':TOKEN,'totp':''},admin=False,headers={'X-Forwarded-For':ip});self.assertEqual(st,201)
        with sqlite3.connect(self.db) as con:
            row=con.execute("SELECT ip FROM audit WHERE action='auth.session' ORDER BY seq DESC LIMIT 1").fetchone()
        self.assertEqual(row[0],ip)

    def test_command_state_does_not_regress(self):
        d=self.enroll('Command');ts=int(time.time());cid='state-'+d['deviceId']
        cmd={'commandId':cid,'idempotencyKey':cid,'type':'sms.send','createdAt':ts,'expiresAt':ts+60,'ciphertext':self.cipher(2)}
        self.assertEqual(self.req('POST',f"/api/v1/devices/{d['deviceId']}/commands",cmd)[0],201)
        self.assertEqual(self.req('POST',f"/api/v1/devices/{d['deviceId']}/commands/{cid}/ack",{'state':'sent','result':{}},device_token=d['deviceToken'],admin=False)[0],200)
        self.assertEqual(self.req('POST',f"/api/v1/devices/{d['deviceId']}/commands/{cid}/ack",{'state':'submitted','result':{}},device_token=d['deviceToken'],admin=False)[0],200)
        with sqlite3.connect(self.db) as con: state=con.execute('SELECT state FROM commands WHERE id=?',(cid,)).fetchone()[0]
        self.assertEqual(state,'sent')

    def test_two_phase_device_token_rotation(self):
        d=self.enroll('Rotate');did=d['deviceId'];old=d['deviceToken']
        st,p,_=self.req('POST',f'/api/v1/devices/{did}/token/prepare',{},device_token=old,admin=False);self.assertEqual(st,201)
        new=p['deviceToken'];self.assertNotEqual(old,new)
        self.assertEqual(self.req('POST',f'/api/v1/devices/{did}/heartbeat',{},device_token=old,admin=False)[0],200)
        st,c,_=self.req('POST',f'/api/v1/devices/{did}/token/commit',{},device_token=new,admin=False);self.assertEqual(st,200);self.assertTrue(c['ok'])
        self.assertEqual(self.req('POST',f'/api/v1/devices/{did}/heartbeat',{},device_token=old,admin=False)[0],401)
        self.assertEqual(self.req('POST',f'/api/v1/devices/{did}/heartbeat',{},device_token=new,admin=False)[0],200)

    def test_expired_pending_token_keeps_current_token_valid(self):
        d=self.enroll('RotateExpiry');did=d['deviceId'];old=d['deviceToken']
        st,p,_=self.req('POST',f'/api/v1/devices/{did}/token/prepare',{},device_token=old,admin=False);self.assertEqual(st,201)
        with sqlite3.connect(self.db) as con:
            con.execute("UPDATE devices SET pending_token_expires_at=? WHERE id=?",(int(time.time())-1,did))
        import simhub_server as _unused  # documentation of server-side maintenance semantics
        self.assertEqual(self.req('POST',f'/api/v1/devices/{did}/heartbeat',{},device_token=old,admin=False)[0],200)
        st,_,_=self.req('POST',f'/api/v1/devices/{did}/heartbeat',{},device_token=p['deviceToken'],admin=False);self.assertEqual(st,401)

    def test_phone_commands_rejected(self):
        d=self.enroll()
        st,r,_=self.req('POST',f"/api/v1/devices/{d['deviceId']}/commands",{'type':'call.place','ciphertext':self.cipher()})
        self.assertEqual(st,400);self.assertEqual(r['error'],'command_not_allowed')

    def test_bootstrap_enrollment_is_single_use_and_erased(self):
        kid='abcdefgh1234';wrapped=self.cipher(1);bootstrap=self.cipher(1);proof='Q'*43
        st,x,_=self.req('POST','/api/v1/enrollments',{'nodeType':'android','capabilities':['sms.receive'],'keyId':kid,'wrappedKey':wrapped,'bootstrapEnvelope':bootstrap,'bootstrapHash':proof});self.assertEqual(st,201)
        st,d,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'bootstrapProof':proof,'name':'Bootstrap'},admin=False);self.assertEqual(st,201)
        self.assertEqual(d['keyId'],kid);self.assertEqual(d['bootstrapEnvelope'],bootstrap)
        with sqlite3.connect(self.db) as con:
            row=con.execute('SELECT used_at,bootstrap_envelope_json,bootstrap_hash FROM enrollment_tokens WHERE id=?',(x['id'],)).fetchone()
        self.assertIsNotNone(row[0]);self.assertEqual(row[1],'{}');self.assertEqual(row[2],'')
        st,_,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'name':'Replay'},admin=False);self.assertEqual(st,401)

    def test_wrong_bootstrap_proof_does_not_consume_token(self):
        kid='abcdefgh1234';wrapped=self.cipher(1);bootstrap=self.cipher(1);proof='R'*43
        st,x,_=self.req('POST','/api/v1/enrollments',{'keyId':kid,'wrappedKey':wrapped,'bootstrapEnvelope':bootstrap,'bootstrapHash':proof});self.assertEqual(st,201)
        st,r,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'bootstrapProof':'S'*43},admin=False);self.assertEqual(st,401);self.assertEqual(r['error'],'invalid_bootstrap_proof')
        with sqlite3.connect(self.db) as con:
            used=con.execute('SELECT used_at FROM enrollment_tokens WHERE id=?',(x['id'],)).fetchone()[0]
        self.assertIsNone(used)
        st,d,_=self.req('POST','/api/v1/enroll',{'token':x['token'],'bootstrapProof':proof},admin=False);self.assertEqual(st,201);self.assertTrue(d['deviceId'])

    def test_concurrent_enrollment_claim(self):
        from concurrent.futures import ThreadPoolExecutor
        status, enrollment, _ = self.req('POST','/api/v1/enrollments',{})
        self.assertEqual(status,201)
        def attempt(index):
            return self.req('POST','/api/v1/enroll',{'token':enrollment['token'],'name':'AtomicEnrollment'},admin=False)[0]
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(attempt,range(12)))
        self.assertEqual(statuses.count(201),1,statuses)
        self.assertTrue(all(code in (201,401) for code in statuses),statuses)

    def test_independent_key_enrollment_requires_bootstrap(self):
        st,r,_=self.req('POST','/api/v1/enrollments',{'keyId':'abcdefgh1234','wrappedKey':self.cipher(1)})
        self.assertEqual(st,400);self.assertEqual(r['error'],'invalid_bootstrap')

    def test_independent_key_enrollment_requires_bootstrap_proof(self):
        st,r,_=self.req('POST','/api/v1/enrollments',{'keyId':'abcdefgh1234','wrappedKey':self.cipher(1),'bootstrapEnvelope':self.cipher(1)})
        self.assertEqual(st,400);self.assertEqual(r['error'],'invalid_bootstrap')

    def test_enrollment_single_use(self):
        st,x,_=self.req('POST','/api/v1/enrollments',{});self.assertEqual(st,201)
        st,_,_=self.req('POST','/api/v1/enroll',{'token':x['token']},admin=False);self.assertEqual(st,201)
        st,_,_=self.req('POST','/api/v1/enroll',{'token':x['token']},admin=False);self.assertEqual(st,401)

if __name__=='__main__':unittest.main(verbosity=2)
