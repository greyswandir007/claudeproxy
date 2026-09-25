-- V5: OAuth-провайдеры. auth_type = api_key | oauth; для oauth — параметры
-- клиентских учётных данных и адрес token endpoint. Секреты, как и api_key,
-- могут быть ${ENV_VAR} (резолвятся при использовании).
-- Токены хранятся в provider_oauth_token (локальная БД, как и api_key
-- провайдеров — шифрование ключом из ENV в бэклоге).

ALTER TABLE provider ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'api_key';
ALTER TABLE provider ADD COLUMN oauth_grant TEXT NOT NULL DEFAULT 'client_credentials';
ALTER TABLE provider ADD COLUMN oauth_client_id TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN oauth_client_secret TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN oauth_token_url TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN oauth_scopes TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN oauth_refresh_token TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS provider_oauth_token (
  provider_id   INTEGER PRIMARY KEY,
  access_token  TEXT    NOT NULL DEFAULT '',
  refresh_token TEXT    NOT NULL DEFAULT '',
  expires_at    INTEGER NOT NULL DEFAULT 0,
  updated_at    INTEGER NOT NULL
);
