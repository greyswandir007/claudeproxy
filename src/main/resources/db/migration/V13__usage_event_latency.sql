-- M22: латентность запросов — время до первого токена от провайдера и полная
-- длительность ответа провайдера (duration_ms остаётся общим временем ответа
-- клиенту, включая кэш и пересборку).
ALTER TABLE usage_event ADD COLUMN ttft_milliseconds INTEGER;
ALTER TABLE usage_event ADD COLUMN upstream_duration_milliseconds INTEGER;
