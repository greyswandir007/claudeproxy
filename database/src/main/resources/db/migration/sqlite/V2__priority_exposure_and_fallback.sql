-- V2: приоритет выбора, флаги выдачи моделей, дубли public-имён от разных провайдеров.
-- Публичное имя перестаёт глобально уникальным (уникальность — в рамках провайдера):
-- несколько провайдеров могут отдавать одну и ту же модель, выбор — по priority
-- (меньше = выше), при ошибке класса quota/service unavailable — переключение
-- на следующий маршрут. exposed управляет выдачей GET /v1/models.

CREATE TABLE model_rebuilt (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id          INTEGER NOT NULL,
  public_name          TEXT    NOT NULL,
  upstream_name        TEXT    NOT NULL,
  reasoning            TEXT    NOT NULL DEFAULT 'map',
  max_completion_param INTEGER NOT NULL DEFAULT 0,
  priority             INTEGER NOT NULL DEFAULT 100,
  exposed              INTEGER NOT NULL DEFAULT 1,
  enabled              INTEGER NOT NULL DEFAULT 1,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  UNIQUE (provider_id, public_name)
);

INSERT INTO model_rebuilt
  (id, provider_id, public_name, upstream_name, reasoning, max_completion_param,
   priority, exposed, enabled, created_at, updated_at)
SELECT id, provider_id, public_name, upstream_name, reasoning, max_completion_param,
       100, 1, enabled, created_at, updated_at
FROM model;

DROP TABLE model;
ALTER TABLE model_rebuilt RENAME TO model;
CREATE INDEX IF NOT EXISTS idx_model_provider ON model(provider_id);

ALTER TABLE provider ADD COLUMN exposed INTEGER NOT NULL DEFAULT 1;
