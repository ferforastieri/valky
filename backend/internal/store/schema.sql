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
  icon TEXT NOT NULL DEFAULT 'camera',
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
  confirmations INTEGER NOT NULL,
  cooldown_seconds INTEGER NOT NULL,
  schedule_json TEXT NOT NULL,
  actions_json TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  last_triggered_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
-- Rule idempotency is indexed after column migrations in store.Open.


CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  camera_id TEXT REFERENCES cameras(id) ON DELETE CASCADE,
  rule_id TEXT REFERENCES rules(id) ON DELETE SET NULL,
  source TEXT NOT NULL DEFAULT 'camera',
  subject_id TEXT NOT NULL DEFAULT '',
  type TEXT NOT NULL,
  confidence REAL NOT NULL,
  occurred_at TEXT NOT NULL,
  snapshot_path TEXT,
  clip_path TEXT,
  clip_status TEXT NOT NULL DEFAULT 'not_requested',
  clip_error TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  acknowledged_at TEXT,
  acknowledged_by TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_occurred ON events(occurred_at DESC);

-- A user is the person shown on the family map. Devices are technical
-- credentials and may be linked to the same user; they are not the profile.
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#5B5BD6',
  avatar_data TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  last_latitude REAL,
  last_longitude REAL,
  last_accuracy REAL,
  last_located_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS people (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#5B5BD6',
  device_id TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  last_latitude REAL,
  last_longitude REAL,
  last_accuracy REAL,
  last_located_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS places (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  radius_meters REAL NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS person_locations (
  id TEXT PRIMARY KEY,
  person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  accuracy REAL NOT NULL DEFAULT 0,
  occurred_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_person_locations_person_time ON person_locations(person_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS place_memberships (
  person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
  place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
  inside INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(person_id, place_id)
);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
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
CREATE TABLE IF NOT EXISTS user_locations (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  accuracy REAL NOT NULL DEFAULT 0,
  address TEXT NOT NULL DEFAULT '',
  occurred_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_user_locations_user_time ON user_locations(user_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS user_place_memberships (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
  inside INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(user_id, place_id)
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

-- Browser sessions never create family profiles or register for push/location.
CREATE TABLE IF NOT EXISTS viewer_sessions (
 id TEXT PRIMARY KEY,
 token_hash BLOB NOT NULL UNIQUE,
 expires_at TEXT NOT NULL,
 created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS geofence_candidates (
 owner_kind TEXT NOT NULL, owner_id TEXT NOT NULL,
 place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
 inside INTEGER NOT NULL, since_at TEXT NOT NULL, last_at TEXT NOT NULL,
 samples INTEGER NOT NULL, place_version TEXT NOT NULL,
 PRIMARY KEY(owner_kind,owner_id,place_id)
);

CREATE TABLE IF NOT EXISTS location_address_cache (
 cell TEXT PRIMARY KEY,
 address TEXT NOT NULL,
 retry_after INTEGER NOT NULL
);
