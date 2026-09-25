-- V4: маппер effort-уровней и оверрайды входных параметров на провайдера.
-- effort_mapping: JSON {"levels":{"low":"...","medium":"...",...}} — пустые levels
-- = маппер выключен (уровни пробрасываются как есть). Ключи — канонические
-- low/medium/high/xhigh/max; значения — строки, понятные провайдеру.
ALTER TABLE provider ADD COLUMN effort_mapping TEXT NOT NULL DEFAULT '{}';

-- Оверрайды: ключи из каталога ProviderSettingCatalog (код), значения строками;
-- UNIQUE не даёт задвоить один ключ на провайдера.
CREATE TABLE IF NOT EXISTS provider_setting (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id  INTEGER NOT NULL,
  setting_key  TEXT    NOT NULL,
  setting_value TEXT   NOT NULL,
  UNIQUE (provider_id, setting_key)
);
