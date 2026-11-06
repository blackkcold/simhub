# Web history and local search

The inbox reports three different kinds of coverage:

| Measure | Meaning |
| --- | --- |
| Loaded / searchable records | Records currently held in this unlocked tab; text search requires successful local decryption. Counts are events, so one SMS may have several history or delivery records. |
| Relay last reported | The most recent device-list snapshot of retained SMS events. It can change with uploads, retention, or deletion and is not the size of a phone's original SMS database. |
| Node queued events | The latest queue count reported by each node, including non-SMS events. Missing telemetry is shown as unknown. A zero queue does not prove that all phone history has been scanned or uploaded. |

The device selector scopes the displayed counts. SIM, message type and text filters operate on the records already loaded. **Load all Relay history** scans retained records for all devices; it does not silently order nodes to scan or re-upload their local SMS databases. A node history sync remains a separate action in device details.

## Loading and cancellation

Initial loading reads recent records. Explicit backfill requests at most 500 SMS events per page, using the Relay's `(occurredAt, seq)` cursor. Pages are sequential, with a cancellable 750 ms interval between requests to leave request-budget headroom for live refreshes. An invalid or non-advancing cursor stops loading rather than looping or claiming completion.

**Stop** aborts the request or pacing wait and prevents the current page from advancing its checkpoint. Rows already decrypted remain searchable. **Resume history** requests the same unfinished page and deduplicates its already accepted rows. History pagination never moves the independent incremental sequence cursor, so old-dated uploads can still arrive through live synchronization.

Reaching the history end means that this scan reached the end of the Relay's response. It does not prove that every node has uploaded its history, that concurrent uploads have finished, or that unreadable ciphertext was searched. The inbox shows these conditions separately.

## Memory and privacy

The index and decrypted history are **RAM-only**. They are not written to localStorage, sessionStorage, IndexedDB, or the service-worker cache. The search text is never sent to the Relay. Vault lock, device history reset, and explicit cache release clear the index and invalidate in-flight decryption. Reloading the page begins with recent records again.

The trigram posting lists have a 200,000-posting budget and a 4,096-gram budget per row. When those indexing budgets are exhausted, matching falls back to a direct scan of the remaining loaded rows; search does not silently discard their contents. Case-insensitive substring matching uses Unicode NFKC normalization and searches body, sender, recipient, contact name, OTP and the current device label. Text/type filtering precedes folding of outgoing status records, so a later metadata-only delivery record cannot hide an earlier matching body. While a query is active, the conversation pane identifies itself as showing search matches.

Backfill admission stops before adding a row that would exceed **50,000 loaded events or 64 MiB of estimated row and normalized-text storage**. The byte count conservatively estimates UTF-16 strings plus row overhead; it is **not a guarantee about the browser's total heap**, transient network/decryption buffers, or live incremental arrivals. New live records retain priority over bulk history loading.

At this limit, the page keeps its previous cursor, explicitly states that some history remains unsearched, and offers **Release history cache**. Releasing the cache reclaims the loaded history and starts again from recent records. It does not make an oversized history fully searchable: histories exceeding the budget remain partial in this implementation. No percentage or “all phone messages searched” claim is made.

## Verification

`test_history_search.mjs` checks exact Unicode/substring results when posting budgets are exhausted, unreadable-record coverage, row/byte admission, and clearing. `test_history_backfill.mjs` checks cursor progress, page bounds, sequential requests, cancellation, pacing and stale-worker isolation. `test_history_pipeline.mjs` executes the actual app functions for partial-page recovery, generation resets, search integration, memory-limit coverage and aborted read retries.

The browser suite also loads 1,205 synthetic AES-GCM records through mocked Relay responses, cancels and resumes a page, finds the oldest encrypted record, verifies the mobile controls, and checks that search/history plaintext is not persisted across reloads or retained in the DOM after lock. No physical device or SMS transmission is used.
