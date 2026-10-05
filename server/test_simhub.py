#!/usr/bin/env python3
import json, os, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SERVER=ROOT/'server'/'simhub_server.py'
TOKEN='A'*48

def free_port():
    s=socket.socket(); s.bind(('127.0.0.1',0)); p=s.getsockname()[1]; s.close(); return p

class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(); cls.port=free_port(); cls.base=f'http://127.0.0.1:{cls.port}'
        env=os.environ.copy(); env.update({'SIMHUB_ADMIN_TOKEN':TOKEN,'SIMHUB_BIND':'127.0.0.1','SIMHUB_PORT':str(cls.port),'SIMHUB_DB':str(Path(cls.tmp.name)/'test.db'),'SIMHUB_WEB_ROOT':str(ROOT/'web')})
        cls.proc=subprocess.Popen([sys.executable,str(SERVER)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        for _ in range(50):
            try:
                urllib.request.urlopen(cls.base+'/healthz',timeout=.2).read(); break
            except Exception: time.sleep(.05)
        else: raise RuntimeError('server failed to start')
    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate(); cls.proc.wait(timeout=3); cls.tmp.cleanup()
    def req(self, method, path, body=None, device_token=None, admin=True):
        data=None if body is None else json.dumps(body).encode()
        h={'Content-Type':'application/json'}
        if device_token: h['Authorization']='Device '+device_token
        elif admin: h['Authorization']='Bearer '+TOKEN
        req=urllib.request.Request(self.base+path,data=data,headers=h,method=method)
        try:
            with urllib.request.urlopen(req,timeout=2) as r: return r.status,json.loads(r.read())
        except urllib.error.HTTPError as e: return e.code,json.loads(e.read())
    def enroll(self):
        st,x=self.req('POST','/api/v1/enrollments',{}); self.assertEqual(st,201)
        st,d=self.req('POST','/api/v1/enroll',{'token':x['token'],'name':'Pixel SIM Node','model':'Pixel'},admin=False); self.assertEqual(st,201)
        return d
    def test_end_to_end(self):
        d=self.enroll(); did=d['deviceId']; dt=d['deviceToken']
        cipher={'v':1,'alg':'A256GCM','iv':'AAAAAAAAAAAAAAAA','ct':'A'*32}
        ev={'eventId':'evt-1','kind':'sms.received','occurredAt':int(time.time()),'subscriptionId':'1','hasOtp':True,'metadata':{'sender':'must-strip','parts':1},'ciphertext':cipher}
        st,r=self.req('POST',f'/api/v1/devices/{did}/events',ev,device_token=dt,admin=False); self.assertEqual(st,201)
        st,r2=self.req('POST',f'/api/v1/devices/{did}/events',ev,device_token=dt,admin=False); self.assertEqual(st,200); self.assertTrue(r2['duplicate'])
        st,e=self.req('GET','/api/v1/events?since=0'); self.assertEqual(st,200); self.assertEqual(len(e['events']),1); self.assertNotIn('sender',e['events'][0]['metadata'])
        cmd={'commandId':'cmd-1','idempotencyKey':'idem-1','type':'sms.send','expiresAt':int(time.time())+60,'ciphertext':cipher}
        st,c=self.req('POST',f'/api/v1/devices/{did}/commands',cmd); self.assertEqual(st,201)
        st,p=self.req('GET',f'/api/v1/devices/{did}/commands/pending',device_token=dt,admin=False); self.assertEqual(st,200); self.assertEqual(p['commands'][0]['commandId'],'cmd-1')
        st,a=self.req('POST',f'/api/v1/devices/{did}/commands/cmd-1/ack',{'state':'succeeded','result':{'recipient':'must-strip','parts':1}},device_token=dt,admin=False); self.assertEqual(st,200)
    def test_phone_commands_rejected(self):
        d=self.enroll(); cipher={'v':1,'alg':'A256GCM','iv':'AAAAAAAAAAAAAAAA','ct':'A'*32}
        st,r=self.req('POST',f"/api/v1/devices/{d['deviceId']}/commands",{'type':'call.place','ciphertext':cipher})
        self.assertEqual(st,400); self.assertEqual(r['error'],'command_not_allowed')
    def test_enrollment_single_use(self):
        st,x=self.req('POST','/api/v1/enrollments',{}); self.assertEqual(st,201)
        st,_=self.req('POST','/api/v1/enroll',{'token':x['token']},admin=False); self.assertEqual(st,201)
        st,_=self.req('POST','/api/v1/enroll',{'token':x['token']},admin=False); self.assertEqual(st,401)

if __name__=='__main__': unittest.main(verbosity=2)
