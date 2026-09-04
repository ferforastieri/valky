PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cameras (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL DEFAULT 2020,
  username_enc BLOB NOT NULL,
  password_enc BLOB NOT NULL,
  rtsp_uri_enc BLOB NOT NULL,
  profile_token TEXT NOT NULL DEFAULT '',
  capabilities_json TEXT NOT NULL DEFAULT '{}',
  media_xaddr TEXT NOT NULL DEFAULT '',
  events_xaddr TEXT NOT NULL DEFAULT '',
  ptz_xaddr TEXT NOT NULL DEFAULT '',
  setup_status TEXT NOT NULL DEFAULT 'ready',
  setup_step TEXT NOT NULL DEFAULT '',
  setup_error TEXT NOT NULL DEFAULT '',
  setup_updated_at TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rules (
  id TEXT PRIMARY KEY,
  camera_id TEXT NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  detector_types_json TEXT NOT NULL,
  min_confidence REAL NOT NULL,
  confirmations INTEGER NOT NULL,
  cooldown_seconds INTEGER NOT NULL,
  schedule_json TEXT NOT NULL,
  actions_json TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  last_triggered_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  camera_id TEXT NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  rule_id TEXT REFERENCES rules(id) ON DELETE SET NULL,
  type TEXT NOT NULL,
  confidence REAL NOT NULL,
  occurred_at TEXT NOT NULL,
  snapshot_path TEXT,
  clip_path TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  acknowledged_at TEXT,
  acknowledged_by TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_occurred ON events(occurred_at DESC);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_hash BLOB NOT NULL UNIQUE,
  is_admin INTEGER NOT NULL DEFAULT 0,
  push_endpoint_enc BLOB,
  push_secret_enc BLOB,
  locale TEXT NOT NULL DEFAULT 'pt-BR',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  last_seen_at TEXT
);

CREATE TABLE IF NOT EXISTS pairing_sessions (
  id TEXT PRIMARY KEY,
  code_hash BLOB NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS push_deliveries (
  id TEXT PRIMARY KEY,
  event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT NOT NULL,
  delivered_at TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL
);
