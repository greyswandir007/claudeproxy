-- Прокси/туннели доступа к провайдерам (M31): отдельная сущность со своей
-- страницей дашборда. type: HTTP | HTTPS (CONNECT-туннель) | SOCKS4 | SOCKS5.
-- password допускает ${ENV:...} (резолвится при вызове, наружу не отдаётся).
-- Провайдер ссылается на прокси по имени — без жёсткого FK, как
-- provider_resources в V16.
CREATE TABLE IF NOT EXISTS proxy_endpoint (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  name              TEXT NOT NULL UNIQUE,
  type              TEXT NOT NULL,
  host              TEXT NOT NULL,
  port              INTEGER NOT NULL,
  username          TEXT,
  password          TEXT,
  enabled           INTEGER NOT NULL DEFAULT 1,
  last_check_status TEXT,
  last_check_at     INTEGER,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);

ALTER TABLE provider ADD COLUMN proxy_name TEXT;
