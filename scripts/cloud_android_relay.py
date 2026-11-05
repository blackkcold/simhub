#!/usr/bin/env python3
"""Isolated CI Relay over ephemeral TLS for Android emulator acceptance only."""
import argparse
import json
import os
import ssl
import sys
import time
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ORIGIN="https://127.0.0.1:18443"


def serve(cert: Path,key: Path) -> None:
    sys.path.insert(0,str(ROOT/"server"))
    import simhub_server as relay
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert,key)
    base=relay.BoundedHTTPServer
    class TlsRelay(base):
        def __init__(self,address,handler):
            super().__init__(address,handler)
            self.socket=context.wrap_socket(self.socket,server_side=True)
    relay.BoundedHTTPServer=TlsRelay
    relay.main()


def enroll(ca: Path,output: Path) -> None:
    context=ssl.create_default_context(cafile=str(ca))
    token=os.environ["SIMHUB_ADMIN_TOKEN"]
    def call(path,body=None,admin=False):
        headers={"Content-Type":"application/json"}
        if admin:headers["Authorization"]="Bearer "+token
        request=urllib.request.Request(ORIGIN+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
        with urllib.request.urlopen(request,timeout=5,context=context) as response:
            return json.load(response)
    for attempt in range(60):
        try:
            if call("/readyz").get("ok"):break
        except OSError:
            if attempt==59:raise
            time.sleep(1)
    enrollment=call("/api/v1/enrollments",{},admin=True)
    device=call("/api/v1/enroll",{"token":enrollment["token"],"name":"Cloud emulator","model":"CI"})
    fd=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,"w",encoding="utf-8") as handle:
        json.dump({"deviceId":device["deviceId"],"deviceToken":device["deviceToken"]},handle)
    print("Isolated emulator enrollment prepared")


def main() -> None:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("operation",choices=("serve","enroll"))
    p.add_argument("--cert",type=Path);p.add_argument("--key",type=Path)
    p.add_argument("--ca",type=Path);p.add_argument("--output",type=Path)
    args=p.parse_args()
    if args.operation=="serve":
        if not args.cert or not args.key:p.error("serve requires --cert and --key")
        serve(args.cert,args.key)
    else:
        if not args.ca or not args.output:p.error("enroll requires --ca and --output")
        enroll(args.ca,args.output)


if __name__=="__main__":
    main()
