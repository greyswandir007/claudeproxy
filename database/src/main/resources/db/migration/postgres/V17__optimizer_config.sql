-- Настройка модели-оптимизатора (M30): сжатие старых tool_result перед
-- отправкой upstream. Провайдер выбирается из подключённых (по имени, как
-- provider_resources в V16), модель — строкой, которую подставит выбранный
-- провайдер. Таблица однострочная (id = 1).
CREATE TABLE IF NOT EXISTS optimizer_config (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  enabled       INTEGER NOT NULL DEFAULT 0,
  provider_name TEXT,
  model         TEXT,
  updated_at    BIGINT NOT NULL
);
