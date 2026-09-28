-- V3: информационные лимиты токенов на провайдера (5ч / неделя / месяц, NULL = не задан)
-- и 5-часовые окна провайдера для выработки лимита (план окна тот же, что у клиентских
-- ключей: окно стартует с первого обращения после простоя).

ALTER TABLE provider ADD COLUMN limit_window_tokens INTEGER;
ALTER TABLE provider ADD COLUMN limit_week_tokens INTEGER;
ALTER TABLE provider ADD COLUMN limit_month_tokens INTEGER;

CREATE TABLE IF NOT EXISTS provider_usage_window (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_name TEXT    NOT NULL,
  started_at    INTEGER NOT NULL,
  ends_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_provider_usage_window_name ON provider_usage_window(provider_name, ends_at);
