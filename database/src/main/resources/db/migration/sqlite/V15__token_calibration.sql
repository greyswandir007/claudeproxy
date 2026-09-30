-- M28: калибровка оценки count_tokens для openai-провайдеров.
-- Накопленные суммы «текстовые символы запроса → фактические input_tokens»
-- по парам (модель, провайдер); коэффициент = text_characters / text_tokens.
CREATE TABLE IF NOT EXISTS token_calibration (
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    text_characters INTEGER NOT NULL,
    text_tokens INTEGER NOT NULL,
    samples INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (model, provider)
);
