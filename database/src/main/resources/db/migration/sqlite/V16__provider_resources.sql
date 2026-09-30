-- Привязка upstream-ресурсов (батчи, файлы) к провайдеру, на котором они
-- созданы: последующие запросы по идентификатору ресурса идут мимо
-- маршрутизации по модели.
CREATE TABLE provider_resources (
    resource_type TEXT NOT NULL,
    resource_id   TEXT NOT NULL,
    provider_name TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    PRIMARY KEY (resource_type, resource_id)
);

-- Дедуп учёта usage результатов батча: results можно читать многократно.
CREATE TABLE message_batch_usage (
    batch_id  TEXT NOT NULL,
    custom_id TEXT NOT NULL,
    PRIMARY KEY (batch_id, custom_id)
);
