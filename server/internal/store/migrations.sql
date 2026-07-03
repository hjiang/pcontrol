CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY
);

-- Version 1: initial schema
INSERT OR IGNORE INTO schema_migrations (version) VALUES (1);

CREATE TABLE IF NOT EXISTS devices (
    id            INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,
    created_at    TEXT NOT NULL,
    last_seen_at  TEXT
);

CREATE TABLE IF NOT EXISTS usage_events (
    id               INTEGER PRIMARY KEY,
    event_id         TEXT NOT NULL UNIQUE,
    device_id        INTEGER NOT NULL REFERENCES devices(id),
    kind             TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject          TEXT NOT NULL,
    label            TEXT NOT NULL DEFAULT '',
    day              TEXT NOT NULL,
    started_at       TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 0)
);
CREATE INDEX IF NOT EXISTS idx_usage_device_day
    ON usage_events(device_id, day);

CREATE TABLE IF NOT EXISTS limits (
    id                  INTEGER PRIMARY KEY,
    device_id           INTEGER NOT NULL REFERENCES devices(id),
    kind                TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject             TEXT NOT NULL,
    daily_limit_minutes INTEGER NOT NULL CHECK (daily_limit_minutes > 0),
    UNIQUE (device_id, kind, subject)
);

CREATE TABLE IF NOT EXISTS exclusions (
    id        INTEGER PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id),
    kind      TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject   TEXT NOT NULL,
    UNIQUE (device_id, kind, subject)
);

CREATE TABLE IF NOT EXISTS device_settings (
    device_id                 INTEGER PRIMARY KEY REFERENCES devices(id),
    total_daily_limit_minutes INTEGER,
    warn_threshold_percent    INTEGER NOT NULL DEFAULT 90,
    policy_version            INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    expires_at TEXT NOT NULL
);
