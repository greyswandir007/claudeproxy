-- Схема БД claudeproxy (SQLite). Идемпотентна — применяется при каждом старте.
-- Схлопнута 2026-10-16 из V1–V22 в финальную схему одним файлом. Новая база
-- получает её сразу; база, где миграции уже применены, этот файл не
-- переигрывает — раннер пропускает зафиксированные версии.

CREATE TABLE IF NOT EXISTS usage_event (
  id                              INTEGER PRIMARY KEY AUTOINCREMENT,
  ts                              INTEGER NOT NULL,            -- epoch millis UTC
  client_key                      TEXT    NOT NULL,            -- имя клиентского ключа прокси
  provider                        TEXT    NOT NULL,
  model                           TEXT    NOT NULL,            -- public-имя модели
  upstream_model                  TEXT,
  stream                          INTEGER NOT NULL DEFAULT 0,
  input_tokens                    INTEGER NOT NULL DEFAULT 0,
  output_tokens                   INTEGER NOT NULL DEFAULT 0,
  cache_creation_tokens           INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens               INTEGER NOT NULL DEFAULT 0,
  duration_ms                     INTEGER,
  status                          INTEGER,                     -- HTTP-код; 0 = оборван
  error                           TEXT,
  error_detail                    TEXT,                        -- полное тело ошибки провайдера
  saved_tokens                    INTEGER NOT NULL DEFAULT 0,  -- вырезано обрезкой инструментов
  ttft_milliseconds               INTEGER,                     -- время до первого токена
  upstream_duration_milliseconds  INTEGER,                     -- время ответа провайдера
  user_id                         TEXT                         -- сессия клиента (атрибуция)
);
CREATE INDEX IF NOT EXISTS idx_usage_event_ts       ON usage_event(ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_model_ts ON usage_event(model, ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_key_ts   ON usage_event(client_key, ts);

CREATE TABLE IF NOT EXISTS usage_window (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key TEXT    NOT NULL,
  started_at INTEGER NOT NULL,                                 -- epoch millis UTC
  ends_at    INTEGER NOT NULL                                  -- started_at + 5ч
);
CREATE INDEX IF NOT EXISTS idx_usage_window_key ON usage_window(client_key, ends_at);

-- Клиентские ключи прокси: полный ключ не хранится, только SHA-256-хэш;
-- key_prefix (cpk_+первые символы) — для отображения в UI.
-- allowed_models пусто = все модели; лимиты NULL = безлимит по измерению.
CREATE TABLE IF NOT EXISTS api_key (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  name                TEXT    NOT NULL UNIQUE,
  key_hash            TEXT    NOT NULL UNIQUE,
  key_prefix          TEXT    NOT NULL,
  created_at          INTEGER NOT NULL,
  revoked_at          INTEGER,                                 -- NULL = активен
  last_used_at        INTEGER,
  allowed_models      TEXT    NOT NULL DEFAULT '',
  limit_window_tokens INTEGER,
  limit_month_tokens  INTEGER
);

-- Провайдеры и модели: источник истины — БД (YAML — сид при старте, как с ключами).
-- api_key может быть литералом или ссылкой ${ENV_VAR} (резолвится при загрузке реестра).
CREATE TABLE IF NOT EXISTS provider (
  id                       INTEGER PRIMARY KEY AUTOINCREMENT,
  name                     TEXT    NOT NULL UNIQUE,
  type                     TEXT    NOT NULL,                   -- anthropic | openai
  base_url                 TEXT    NOT NULL,
  api_key                  TEXT    NOT NULL DEFAULT '',
  extra_headers            TEXT    NOT NULL DEFAULT '{}',
  created_at               INTEGER NOT NULL,
  updated_at               INTEGER NOT NULL,
  exposed                  INTEGER NOT NULL DEFAULT 1,         -- виден в /v1/models
  limit_window_tokens      INTEGER,
  limit_week_tokens        INTEGER,
  limit_month_tokens       INTEGER,
  effort_mapping           TEXT    NOT NULL DEFAULT '{}',      -- JSON уровней reasoning
  auth_type                TEXT    NOT NULL DEFAULT 'api_key', -- api_key | oauth
  oauth_grant              TEXT    NOT NULL DEFAULT 'client_credentials',
  oauth_client_id          TEXT    NOT NULL DEFAULT '',
  oauth_client_secret      TEXT    NOT NULL DEFAULT '',
  oauth_token_url          TEXT    NOT NULL DEFAULT '',
  oauth_scopes             TEXT    NOT NULL DEFAULT '',
  oauth_refresh_token      TEXT    NOT NULL DEFAULT '',
  pricing_mode             TEXT    NOT NULL DEFAULT '',        -- '' | per_million | monthly
  price_per_million_tokens REAL,
  price_monthly            REAL,
  proxy_name               TEXT                                -- ссылка на proxy_endpoint по имени
);

CREATE TABLE IF NOT EXISTS model (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id          INTEGER NOT NULL,
  public_name          TEXT    NOT NULL,
  upstream_name        TEXT    NOT NULL,
  reasoning            TEXT    NOT NULL DEFAULT 'map',         -- map | off (openai)
  max_completion_param INTEGER NOT NULL DEFAULT 0,
  priority             INTEGER,                                -- меньше — раньше в роутинге; NULL — по дате создания
  exposed              INTEGER NOT NULL DEFAULT 1,             -- видна в /v1/models
  enabled              INTEGER NOT NULL DEFAULT 1,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  UNIQUE (provider_id, public_name)
);
CREATE INDEX IF NOT EXISTS idx_model_provider ON model(provider_id);

-- Оверрайды входных параметров провайдера: ключи из каталога
-- ProviderSettingCatalog, значения строками.
CREATE TABLE IF NOT EXISTS provider_setting (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id   INTEGER NOT NULL,
  setting_key   TEXT    NOT NULL,
  setting_value TEXT    NOT NULL,
  UNIQUE (provider_id, setting_key)
);

-- OAuth-авторизация провайдера: access-токен живёт в памяти, здесь —
-- refresh-токен и срок действия.
CREATE TABLE IF NOT EXISTS provider_oauth_token (
  provider_id   INTEGER PRIMARY KEY,
  access_token  TEXT    NOT NULL DEFAULT '',
  refresh_token TEXT    NOT NULL DEFAULT '',
  expires_at    INTEGER NOT NULL DEFAULT 0,
  updated_at    INTEGER NOT NULL
);

-- Скользящие окна расхода провайдера (лимиты limit_window_*).
CREATE TABLE IF NOT EXISTS provider_usage_window (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_name TEXT    NOT NULL,
  started_at    INTEGER NOT NULL,
  ends_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_provider_usage_window_name
  ON provider_usage_window(provider_name, ends_at);

-- Веб-чат дашборда: на каждый клиентский ключ — свой тред.
CREATE TABLE IF NOT EXISTS chat_thread (
  client_key TEXT    PRIMARY KEY,
  title      TEXT    NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_message (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key TEXT    NOT NULL,
  role       TEXT    NOT NULL,                                -- user | assistant
  content    TEXT    NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_message_key ON chat_message(client_key, id);

-- Кэш повторяющихся запросов: точный хэш тела → сохранённый ответ без похода
-- к провайдеру. TTL фиксируется на строку (expires_at); LRU по last_accessed_at.
CREATE TABLE IF NOT EXISTS request_cache (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  cache_key        TEXT    NOT NULL,
  upstream_path    TEXT    NOT NULL,
  request_body     TEXT    NOT NULL,
  response_body    TEXT    NOT NULL,
  response_format  TEXT    NOT NULL,
  model            TEXT    NOT NULL,
  provider         TEXT    NOT NULL,
  input_tokens     INTEGER NOT NULL DEFAULT 0,
  output_tokens    INTEGER NOT NULL DEFAULT 0,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  last_accessed_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_request_cache_key
  ON request_cache(cache_key, upstream_path);
CREATE INDEX IF NOT EXISTS idx_request_cache_last_accessed
  ON request_cache(last_accessed_at);

-- События сервера (WARN/ERROR) для вкладки дашборда.
CREATE TABLE IF NOT EXISTS server_event (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,                               -- epoch millis UTC
  level       TEXT    NOT NULL,                               -- WARN | ERROR
  logger      TEXT    NOT NULL,
  message     TEXT    NOT NULL,
  stack_trace TEXT                                            -- стек исключения, если было
);
CREATE INDEX IF NOT EXISTS idx_server_event_ts ON server_event (ts DESC);

-- Калибровка оценки count_tokens для openai-провайдеров:
-- коэффициент = text_characters / text_tokens по паре (модель, провайдер).
CREATE TABLE IF NOT EXISTS token_calibration (
  model           TEXT    NOT NULL,
  provider        TEXT    NOT NULL,
  text_characters INTEGER NOT NULL,
  text_tokens     INTEGER NOT NULL,
  samples         INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  PRIMARY KEY (model, provider)
);

-- Привязка upstream-ресурсов (батчи, файлы) к провайдеру, на котором они
-- созданы: запросы по идентификатору ресурса идут мимо маршрутизации по модели.
CREATE TABLE IF NOT EXISTS provider_resources (
  resource_type TEXT    NOT NULL,
  resource_id   TEXT    NOT NULL,
  provider_name TEXT    NOT NULL,
  created_at    INTEGER NOT NULL,
  PRIMARY KEY (resource_type, resource_id)
);

-- Дедуп учёта usage результатов батча: results можно читать многократно.
CREATE TABLE IF NOT EXISTS message_batch_usage (
  batch_id  TEXT NOT NULL,
  custom_id TEXT NOT NULL,
  PRIMARY KEY (batch_id, custom_id)
);

-- Настройка модели-оптимизатора: сжатие старых tool_result перед отправкой
-- upstream. Таблица однострочная (id = 1).
CREATE TABLE IF NOT EXISTS optimizer_config (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  enabled       INTEGER NOT NULL DEFAULT 0,
  provider_name TEXT,
  model         TEXT,
  updated_at    INTEGER NOT NULL
);

-- Прокси/туннели доступа к провайдерам. type: HTTP | HTTPS | SOCKS4 | SOCKS5.
-- Провайдер ссылается на прокси по имени (provider.proxy_name), без жёсткого FK.
CREATE TABLE IF NOT EXISTS proxy_endpoint (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  name              TEXT    NOT NULL UNIQUE,
  type              TEXT    NOT NULL,
  host              TEXT    NOT NULL,
  port              INTEGER NOT NULL,
  username          TEXT,
  password          TEXT,
  enabled           INTEGER NOT NULL DEFAULT 1,
  last_check_status TEXT,
  last_check_at     INTEGER,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);

-- Внешние кэш-провайдеры: прокси — клиент, устройство кэша ему неизвестно.
CREATE TABLE IF NOT EXISTS cache_provider (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL UNIQUE,                        -- имя, на него ссылается CACHE_PROVIDER
  url         TEXT    NOT NULL,                               -- базовый URL сервиса
  enabled     INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
