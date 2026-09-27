-- Журнал событий сервера: записи уровня INFO/WARN/ERROR для страницы «События».
CREATE TABLE IF NOT EXISTS server_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    level TEXT NOT NULL,
    logger TEXT NOT NULL,
    message TEXT NOT NULL,
    stack_trace TEXT
);

CREATE INDEX IF NOT EXISTS idx_server_event_ts ON server_event (ts DESC);
