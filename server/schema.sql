PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS enrollment_tokens(
  id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL, used_at INTEGER
);
CREATE TABLE IF NOT EXISTS devices(
  id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  group_name TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', os_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, last_seen_at INTEGER,
  revoked_at INTEGER
);
CREATE TABLE IF NOT EXISTS events(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
  kind TEXT NOT NULL, occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
  subscription_id TEXT NOT NULL DEFAULT '', has_otp INTEGER NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL DEFAULT '{}', ciphertext_json TEXT NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE INDEX IF NOT EXISTS idx_events_device_seq ON events(device_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_kind_seq ON events(kind, seq);
CREATE TABLE IF NOT EXISTS commands(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
  type TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE, ciphertext_json TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'queued', ack_at INTEGER, result_json TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE INDEX IF NOT EXISTS idx_commands_device_state ON commands(device_id, state, seq);
CREATE TABLE IF NOT EXISTS device_state(
  device_id TEXT PRIMARY KEY, state_json TEXT NOT NULL, updated_at INTEGER NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE TABLE IF NOT EXISTS audit(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, action TEXT NOT NULL,
  target TEXT NOT NULL DEFAULT '', result TEXT NOT NULL, ip TEXT NOT NULL DEFAULT ''
);
