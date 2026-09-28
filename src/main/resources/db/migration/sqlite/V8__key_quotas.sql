-- V8: квоты клиентских ключей. allowed_models (пусто = все модели);
-- limit_window_tokens / limit_month_tokens (NULL = безлимит по измерению;
-- оба NULL = полностью безлимитный доступ).
ALTER TABLE api_key ADD COLUMN allowed_models TEXT NOT NULL DEFAULT '';
ALTER TABLE api_key ADD COLUMN limit_window_tokens INTEGER;
ALTER TABLE api_key ADD COLUMN limit_month_tokens INTEGER;
