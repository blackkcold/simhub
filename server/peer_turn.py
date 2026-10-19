"""Optional, short-lived TURN REST credentials for authenticated Peer sessions.

The deployment operator supplies an allowlisted TURN origin and a coturn auth
secret. The center returns credentials only during the already authorized
controller-create / node-join signaling handshake. Nothing is accepted from an
untrusted signaling frame and credentials are never logged or persisted.
"""
from __future__ import annotations

import os
import re
import secrets

try:
    from .peer_connect import issue_coturn_rest_credential
except ImportError:
    from peer_connect import issue_coturn_rest_credential

# Explicit operator-owned endpoints only. No browser, node or peer may select
# TURN destinations through signaling frames.
_TURN_URL = re.compile(
    r"^turns?:[A-Za-z0-9][A-Za-z0-9.-]{0,251}(?::[0-9]{1,5})?"
    r"(?:\?transport=(?:udp|tcp))?$"
)

def configured_ice(now: int) -> list[dict]:
    secret = os.environ.get("SIMHUB_PEER_TURN_SECRET", "")
    raw_urls = os.environ.get("SIMHUB_PEER_TURN_URLS", "")
    if not secret or not raw_urls:
        return []
    # Reject weak/malformed configuration rather than issuing reusable secrets.
    if len(secret) < 32 or len(secret) > 4096:
        return []
    urls = []
    for raw in raw_urls.split(","):
        url = raw.strip()
        if not url or len(url) > 280 or not _TURN_URL.fullmatch(url):
            return []
        # Only allow explicitly configured TURN servers; no STUN or arbitrary URLs.
        if url not in urls:
            urls.append(url)
        if len(urls) > 4:
            return []
    username, credential = issue_coturn_rest_credential(secret, now, ttl=600)
    return [{"urls": urls, "username": username, "credential": credential}]
