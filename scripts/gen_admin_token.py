#!/usr/bin/env python3
import argparse, secrets
p=argparse.ArgumentParser()
p.add_argument('--raw', action='store_true')
a=p.parse_args()
t=secrets.token_urlsafe(48)
print(t if a.raw else f"SIMHUB_ADMIN_TOKEN={t}")
