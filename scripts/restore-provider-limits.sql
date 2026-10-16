-- Восстановление лимитов/тарифов из локальной БД проекта (выжившие значения).
-- Выполнять ТОЛЬКО при остановленном прокси (D:	oolsi\claudeproxy\data\claudeproxy.db):
--   sqlite3 D:	oolsi\claudeproxy\data\claudeproxy.db restore-providers.sql

UPDATE provider SET limit_window_tokens=200000000, limit_week_tokens=1000000000, limit_month_tokens=NULL, pricing_mode='monthly', price_per_million_tokens=NULL, price_monthly=57.0 WHERE name = 'Z.AI';
UPDATE provider SET limit_window_tokens=14000000, limit_week_tokens=NULL, limit_month_tokens=183510000, pricing_mode='monthly', price_per_million_tokens=NULL, price_monthly=39.0 WHERE name = 'kimi';
