-- Схема БД claudeproxy (SQLite). Идемпотентна — применяется при каждом старте.

CREATE TABLE IF NOT EXISTS usage_event (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  ts                    INTEGER NOT NULL,            -- epoch millis UTC
  client_key            TEXT    NOT NULL,            -- имя клиентского ключа прокси
  provider              TEXT    NOT NULL,
  model                 TEXT    NOT NULL,            -- public-имя модели
  upstream_model        TEXT,
  stream                INTEGER NOT NULL DEFAULT 0,
  input_tokens          INTEGER NOT NULL DEFAULT 0,
  output_tokens         INTEGER NOT NULL DEFAULT 0,
  cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
  duration_ms           INTEGER,
  status                INTEGER,                     -- HTTP-код; 0 = оборван
  error                 TEXT
);
CREATE INDEX IF NOT EXISTS idx_usage_event_ts        ON usage_event(ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_model_ts  ON usage_event(model, ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_key_ts    ON usage_event(client_key, ts);

CREATE TABLE IF NOT EXISTS usage_window (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key TEXT    NOT NULL,
  started_at INTEGER NOT NULL,                       -- epoch millis UTC
  ends_at    INTEGER NOT NULL                        -- started_at + 5ч
);
CREATE INDEX IF NOT EXISTS idx_usage_window_key ON usage_window(client_key, ends_at);

-- Клиентские ключи прокси: полный ключ не хранится, только SHA-256-хэш;
-- key_prefix (cpk_+первые символы) — для отображения в UI.
CREATE TABLE IF NOT EXISTS api_key (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT    NOT NULL UNIQUE,
  key_hash     TEXT    NOT NULL UNIQUE,
  key_prefix   TEXT    NOT NULL,
  created_at   INTEGER NOT NULL,
  revoked_at   INTEGER,                      -- NULL = активен
  last_used_at INTEGER
);
