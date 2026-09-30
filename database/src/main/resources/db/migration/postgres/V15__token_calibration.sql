-- M28: калибровка оценки count_tokens для openai-провайдеров.
-- Накопленные суммы «текстовые символы запроса → фактические input_tokens»
-- по парам (модель, провайдер); коэффициент = text_characters / text_tokens.
CREATE TABLE IF NOT EXISTS token_calibration (
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    text_characters BIGINT NOT NULL,
    text_tokens BIGINT NOT NULL,
    samples BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (model, provider)
);
