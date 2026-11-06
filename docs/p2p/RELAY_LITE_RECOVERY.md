# Relay Lite delivery and local cache recovery

Relay Lite remains an experimental Dev-only path. A relay acceptance means
encrypted bytes are queued; it does not mean a device submitted or delivered an
SMS. Route fallback retains the same request ID and the existing node receipt.

## Mailbox progress

- A pull leases offered envelopes for 30 seconds. A lost response or transient
  network/storage/Keystore failure can retry after the lease; other ready
  envelopes are still eligible immediately.
- Each pull offers at most one envelope per source, ordered by source offer age
  and oldest eligible message. Unprocessable heads cannot hold the entire inbox
  until the 72-hour TTL. Offer order lives in separate per-source scheduling
  rows, so successful ACKs and empty/refilled queues cannot reset a busy source's
  priority. New sources join the current scheduling tail. Scheduling metadata
  is capped at 4,000 rows, expires after 72 hours of inactivity once the queue is
  empty, and is removed on peer revocation. Capacity applies backpressure before
  accepting a new source.
- An authenticated recipient can send a separate `relay.reject` receipt for
  invalid envelopes, revoked/unknown sources, or a lost response cache. It is
  scoped to that recipient and is never a successful application ACK. The relay
  drops rejected ciphertext and retains bounded digest/reason/expiry metadata so
  the same envelope cannot be reinserted before expiry. Changed bytes under that
  ID are refused. A source retry sees an explicit indeterminate/rejected route
  result, not a fabricated carrier-delivery result.
- Negative receipts are capped at 4,000 entries and expire with their original
  envelopes. At capacity the operation fails closed; visibility leases still
  permit unrelated traffic. Existing mailbox row/byte limits remain in force.

## Encrypted local cache

New results and response frames use a separate SQLite cache, encrypted under a
key derived from the current peer content key and a dedicated domain. Peer ID,
pair generation, request ID, content kind and expiry are authenticated bindings.
The cache holds at most 4,096 rows / 12 MiB of ciphertext, with 1,024 rows / 3 MiB
per peer and 96 KiB per encrypted value. Entries expire within the maximum
offline TTL. A new pair generation cannot read a prior generation's payloads.

A durable local claim precedes dispatch. Payload eviction preserves mutation
identity tombstones until their authenticated expiry; retrying a claimed or
evicted mutation returns an indeterminate result and cannot trigger a second
dispatch. Ordinary read-only RPC entries may be discarded under row pressure.
The existing permanent SMS receipt in `PeerStore` remains the authoritative
cross-route deduplication record. Cache exhaustion applies backpressure before
dispatch and never turns an unknown command into a new send.

Revocation and re-pairing clear that peer's cache and old preference entries.
Outgoing requests acquire a target-peer publication lease before preparing their
frame and retain it through relay submission. Publication of a prepared response
checks the source peer's current content-key generation again immediately before
acquiring its publication lease. Revocation
invalidates the peer first, prevents new leases and waits for that peer's active
publications to finish before reporting completion. This wait releases the
global store monitor, so unrelated pair activity continues. If an active network
operation does not drain within 45 seconds, revocation remains applied but the
operation reports a pending-drain error; it does not falsely report completion.
A response prepared before a completed revoke or re-pair cannot be published.
RTC timeout cleanup cancels queued sends, or drains an enqueue already running,
before releasing its publication lease; a delayed serial-executor task cannot
publish after that cleanup returns.
Ciphertext already published before revocation cannot be recalled from a peer.

For an upgrade, existing encrypted preference values are read-only for one
maximum offline window (72 hours from first cache initialization), allowing
in-flight response identities to survive. New traffic never adds those entries;
the old prefixes are erased on subsequent cache maintenance after the deadline.

## Validation

The Dev Android instrumentation suite exercises actual SQLite/Keystore-backed
pair storage, source fairness, negative-receipt scoping, duplicate rejection,
payload and row capacity, tombstone survival, expiry and revocation. Pure JVM
tests cover authenticated offline frames, TTL/domain/key binding, malformed
nonce/tag rejection, and queued RTC send cancellation and drain behavior.
Real-phone background scheduling, carrier delivery and
public-network NAT reliability still require the hardware validation matrix.
