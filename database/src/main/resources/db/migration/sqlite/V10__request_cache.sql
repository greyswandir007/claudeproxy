-- V10: кэш повторяющихся запросов: точный хэш тела запроса → сохранённый
-- ответ без похода к провайдеру. TTL фиксируется на строку (expires_at)
-- настройкой провайдера REQUEST_CACHE_TTL_MS (0 = кэш выключен); повторы
-- бесплатны — в usage_event пишется событие с provider='cache' и saved_tokens.
-- LRU-вытеснение по last_accessed_at, лимит строк — claudeproxy.request-cache.max-rows.
CREATE TABLE IF NOT EXISTS request_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    cache_key TEXT NOT NULL,
    upstream_path TEXT NOT NULL,
    request_body TEXT NOT NULL,
    response_body TEXT NOT NULL,
    response_format TEXT NOT NULL,
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    last_accessed_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_request_cache_key ON request_cache(cache_key, upstream_path);
CREATE INDEX IF NOT EXISTS idx_request_cache_last_accessed ON request_cache(last_accessed_at);
