"""Regression: authenticated, encrypted, revocable one-shot location storage."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
TOKEN="L"*48

class LocationPrivacyIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        port_sock=socket.socket();port_sock.bind(("127.0.0.1",0))
        port=port_sock.getsockname()[1];port_sock.close()
        cls.base=f"http://127.0.0.1:{port}"
        cls.temp=tempfile.TemporaryDirectory()
        env={**os.environ,"SIMHUB_ADMIN_TOKEN":TOKEN,"SIMHUB_REQUIRE_TOTP":"false",
            "SIMHUB_BIND":"127.0.0.1","SIMHUB_PORT":str(port),
            "SIMHUB_DB":str(Path(cls.temp.name)/"location.db"),
            "SIMHUB_WEB_ROOT":str(ROOT/"web"),"SIMHUB_TRUST_DOCKER_GATEWAY":"false"}
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],
            env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        for _ in range(120):
            try:urllib.request.urlopen(cls.base+"/healthz",timeout=.3).read();break
            except Exception:time.sleep(.05)
        else:raise RuntimeError("Location integration Relay failed startup")
        _,reservation=cls.call("POST","/api/v1/enrollments",{},admin=True)
        _,enrolled=cls.call("POST","/api/v1/enroll",
            {"token":reservation["token"],"name":"Location Node","model":"Android"},
            admin=False)
        cls.device_id=enrolled["deviceId"]
        cls.device_token=enrolled["deviceToken"]

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.temp.cleanup()

    @classmethod
    def call(cls,method,path,body=None,admin=True,device=False):
        headers={"Content-Type":"application/json"}
        if device:
            headers["Authorization"]="Device "+cls.device_token
            headers["X-SimHub-Device-Id"]=cls.device_id
        elif admin:headers["Authorization"]="Bearer "+TOKEN
        request=urllib.request.Request(cls.base+path,method=method,headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(request,timeout=5) as result:
                return result.status,json.load(result)
        except urllib.error.HTTPError as error:
            return error.code,json.load(error)

    @classmethod
    def event(cls,epoch,stamp,kind="device.location"):
        return {"eventId":"location-"+str(uuid.uuid4()),"kind":kind,
                "occurredAt":stamp,"subscriptionId":"-1","hasOtp":False,
                "metadata":{"locationEpoch":epoch,"latitude":34.0,"longitude":-118.0},
                "ciphertext":{"v":2,"alg":"A256GCM","kid":"abcdefgh1234",
                    "iv":"AAAAAAAAAAAAAAAA","ct":"A"*32}}

    def test_01_location_requires_explicit_device_epoch(self):
        now=int(time.time())
        old=str(uuid.uuid4())
        eid=self.event(old,now+1)
        code,response=self.call("POST",f"/api/v1/devices/{self.device_id}/events",eid,device=True)
        self.assertEqual(code,200)
        self.assertTrue(response["suppressed"])
        self.assertEqual(self.call("GET",f"/api/v1/devices/{self.device_id}/location")[1]["location"],None)
        self.assertNotEqual(self.call("GET",f"/api/v1/devices/{self.device_id}/location",admin=False)[0],200)
        self.assertNotEqual(self.call("POST",f"/api/v1/devices/{self.device_id}/location/activate",
            {"epoch":old},admin=False)[0],200)
        code,_=self.call("POST",f"/api/v1/devices/{self.device_id}/location/activate",
            {"epoch":"not-a-revision"},device=True)
        self.assertEqual(code,400)

    def test_02_upload_encrypt_only_last_snapshot_and_purge(self):
        first=str(uuid.uuid4())
        code,response=self.call("POST",f"/api/v1/devices/{self.device_id}/location/activate",
            {"epoch":first},device=True)
        self.assertEqual(code,200,response)
        now=int(time.time())
        event=self.event(first,now+1)
        code,r=self.call("POST",f"/api/v1/devices/{self.device_id}/events",event,device=True)
        self.assertEqual(code,201,r)
        code,details=self.call("GET",f"/api/v1/devices/{self.device_id}/location")
        self.assertEqual(code,200)
        self.assertEqual(details["location"]["eventId"],event["eventId"])
        self.assertNotIn("latitude",json.dumps(details))
        newer=self.event(first,now+2)
        code,response=self.call("POST",f"/api/v1/devices/{self.device_id}/events/batch",
            {"events":[newer]},device=True)
        self.assertEqual(code,200,response)
        self.assertEqual(self.call("GET",f"/api/v1/devices/{self.device_id}/location")[1]["location"]["eventId"],
            newer["eventId"])
        code,response=self.call("POST",f"/api/v1/devices/{self.device_id}/location/purge",{},device=True)
        self.assertEqual(code,200,response)
        self.assertIsNone(self.call("GET",f"/api/v1/devices/{self.device_id}/location")[1]["location"])
        delayed=self.event(first,now+4)
        code,response=self.call("POST",f"/api/v1/devices/{self.device_id}/events/batch",
            {"events":[delayed]},device=True)
        self.assertEqual(code,200,response)
        self.assertTrue(response["results"][0]["suppressed"],response)
        # New local consent with a fresh UUID cannot resurrect an old revocation epoch.
        second=str(uuid.uuid4())
        self.assertEqual(self.call("POST",f"/api/v1/devices/{self.device_id}/location/activate",
            {"epoch":second},device=True)[0],200)
        still_old=self.event(first,now+5)
        self.assertTrue(self.call("POST",f"/api/v1/devices/{self.device_id}/events",
            still_old,device=True)[1]["suppressed"])
        current=self.event(second,now+6)
        self.assertEqual(self.call("POST",f"/api/v1/devices/{self.device_id}/events",
            current,device=True)[0],201)
        got=self.call("GET",f"/api/v1/devices/{self.device_id}/location")[1]["location"]
        self.assertEqual(got["eventId"],current["eventId"])
        self.assertEqual(set(got),{"deviceId","eventId","kind","occurredAt",
            "receivedAt","subscriptionId","hasOtp","ciphertext"})

if __name__=="__main__":
    unittest.main(verbosity=2)
