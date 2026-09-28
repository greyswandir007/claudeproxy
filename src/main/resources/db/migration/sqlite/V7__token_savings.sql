-- V7: экономия токенов. Инструменты — оверрайды каталога
-- (CACHE_INJECTION, TRIM_OLD_TOOL_RESULTS, DROP_OLD_TOOL_IMAGES, см.
-- ProviderSettingCatalog); вырезанные токены фиксируются в usage_event.

ALTER TABLE usage_event ADD COLUMN saved_tokens INTEGER NOT NULL DEFAULT 0;
