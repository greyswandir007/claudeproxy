-- V9: тарификация провайдера. pricing_mode ('' | per_million | monthly)
-- и ровно одна цена; вторая величина — расчётная из месячного лимита
-- (в БД не хранится, считается на лету).
ALTER TABLE provider ADD COLUMN pricing_mode TEXT NOT NULL DEFAULT '';
ALTER TABLE provider ADD COLUMN price_per_million_tokens REAL;
ALTER TABLE provider ADD COLUMN price_monthly REAL;
