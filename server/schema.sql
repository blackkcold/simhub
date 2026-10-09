PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS enrollment_tokens(
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  node_type TEXT NOT NULL DEFAULT 'android',
  capabilities_json TEXT NOT NULL DEFAULT '[]',
  key_id TEXT NOT NULL DEFAULT '',
  wrapped_key_json TEXT NOT NULL DEFAULT '{}',
  bootstrap_envelope_json TEXT NOT NULL DEFAULT '{}',
  bootstrap_hash TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS admin_sessions(
  token_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  ip TEXT NOT NULL DEFAULT '',
  admin_fingerprint TEXT NOT NULL DEFAULT '',
  elevated_until INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_expiry ON admin_sessions(expires_at);

CREATE TABLE IF NOT EXISTS devices(
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  group_name TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  os_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER,
  node_type TEXT NOT NULL DEFAULT 'android',
  capabilities_json TEXT NOT NULL DEFAULT '[]',
  key_id TEXT NOT NULL DEFAULT '',
  wrapped_key_json TEXT NOT NULL DEFAULT '{}',
  pending_key_id TEXT NOT NULL DEFAULT '',
  pending_wrapped_key_json TEXT NOT NULL DEFAULT '{}',
  token_issued_at INTEGER NOT NULL DEFAULT 0,
  pending_token_hash TEXT NOT NULL DEFAULT '',
  pending_token_expires_at INTEGER NOT NULL DEFAULT 0,
  sms_purged_before INTEGER NOT NULL DEFAULT 0,
  sms_epoch INTEGER NOT NULL DEFAULT 0,
  reset_requested_at INTEGER NOT NULL DEFAULT 0,
  reset_source TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS events(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  subscription_id TEXT NOT NULL DEFAULT '',
  has_otp INTEGER NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  ciphertext_json TEXT NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id),
  UNIQUE(device_id,id)
);
CREATE INDEX IF NOT EXISTS idx_events_device_seq ON events(device_id,seq);
CREATE INDEX IF NOT EXISTS idx_events_kind_seq ON events(kind,seq);
CREATE INDEX IF NOT EXISTS idx_events_occurred_seq ON events(occurred_at DESC,seq DESC);

CREATE TABLE IF NOT EXISTS commands(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  device_id TEXT NOT NULL,
  type TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  idempotency_key TEXT NOT NULL,
  ciphertext_json TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'queued',
  ack_at INTEGER,
  result_json TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY(device_id) REFERENCES devices(id),
  UNIQUE(device_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_commands_device_state ON commands(device_id,state,seq);

CREATE TABLE IF NOT EXISTS device_state(
  device_id TEXT PRIMARY KEY,
  state_json TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id)
);

CREATE TABLE IF NOT EXISTS subscriptions(
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  android_sub_id TEXT NOT NULL,
  slot_index INTEGER NOT NULL DEFAULT -1,
  carrier_name TEXT NOT NULL DEFAULT '',
  display_name TEXT NOT NULL DEFAULT '',
  is_embedded INTEGER NOT NULL DEFAULT 0,
  state_json TEXT NOT NULL DEFAULT '{}',
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id),
  UNIQUE(device_id,android_sub_id)
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_device ON subscriptions(device_id,last_seen_at);

CREATE TABLE IF NOT EXISTS audit(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at INTEGER NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL DEFAULT '',
  result TEXT NOT NULL,
  ip TEXT NOT NULL DEFAULT ''
);


CREATE TABLE IF NOT EXISTS channels(
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  local_id TEXT NOT NULL DEFAULT '',
  kind TEXT NOT NULL DEFAULT 'sim',
  revision INTEGER NOT NULL DEFAULT 1,
  slot_index INTEGER NOT NULL DEFAULT -1,
  carrier_name TEXT NOT NULL DEFAULT '',
  display_name TEXT NOT NULL DEFAULT '',
  state_json TEXT NOT NULL DEFAULT '{}',
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id),
  UNIQUE(device_id,id)
);
CREATE INDEX IF NOT EXISTS idx_channels_device ON channels(device_id,last_seen_at);

-- A single bootstrap administrator has multiple discoverable FIDO2 passkeys.
-- Public verification keys are NOT Vault encryption keys.
CREATE TABLE IF NOT EXISTS admin_passkeys(
  credential_id TEXT PRIMARY KEY,
  public_key BLOB NOT NULL,
  sign_count INTEGER NOT NULL DEFAULT 0,
  label TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER
);
CREATE TABLE IF NOT EXISTS passkey_challenges(
  id TEXT PRIMARY KEY,
  challenge TEXT NOT NULL,
  kind TEXT NOT NULL,
  username TEXT NOT NULL,
  session_hash TEXT NOT NULL DEFAULT '',
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_passkey_challenges_expiry ON passkey_challenges(expires_at);

-- Vault-wrapped historical Node Keys (Relay cannot decrypt any key).
-- Preserve old ciphertext readability when the node completes a key rotation.
CREATE TABLE IF NOT EXISTS device_key_history(
  device_id TEXT NOT NULL,
  key_id TEXT NOT NULL,
  wrapped_key_json TEXT NOT NULL,
  archived_at INTEGER NOT NULL,
  PRIMARY KEY(device_id,key_id),
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE INDEX IF NOT EXISTS idx_key_history_recent ON device_key_history(device_id,archived_at DESC);

-- Expiring minimal acknowledgements for devices deleted while offline.
CREATE TABLE IF NOT EXISTS device_reset_tombstones(
  device_id TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY(device_id,token_hash)
);
CREATE INDEX IF NOT EXISTS idx_reset_tombstones_expiry ON device_reset_tombstones(expires_at);

-- Device-initiated pairing: only opaque, time-limited proofs are stored.
CREATE TABLE IF NOT EXISTS pairing_requests(
  id TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  poll_token_hash TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  os_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  device_public_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending',
  attempts INTEGER NOT NULL DEFAULT 0,
  device_id TEXT NOT NULL,
  device_token_hash TEXT NOT NULL DEFAULT '',
  key_id TEXT NOT NULL DEFAULT '',
  wrapped_key_json TEXT NOT NULL DEFAULT '{}',
  envelope_json TEXT NOT NULL DEFAULT '{}',
  complete_proof_hash TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_pairing_requests_expiry ON pairing_requests(expires_at);
