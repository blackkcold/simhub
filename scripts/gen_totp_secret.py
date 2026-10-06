#!/usr/bin/env python3
import base64, secrets

print(base64.b32encode(secrets.token_bytes(20)).decode().rstrip("="))
