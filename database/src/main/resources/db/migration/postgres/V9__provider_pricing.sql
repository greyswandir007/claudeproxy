-- V9: тарификация провайдера. pricing_mode ('' | per_million | monthly)
-- и ровно одна цена; вторая величина — расчётная из месячного лимита
-- (в БД не хранится, считается на лету). REAL SQLite — 8 байт,
-- в PostgreSQL ему соответствует DOUBLE PRECISION.
ALTER TABLE provider ADD COLUMN pricing_mode TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN price_per_million_tokens DOUBLE PRECISION;
ALTER TABLE provider ADD COLUMN price_monthly DOUBLE PRECISION;
