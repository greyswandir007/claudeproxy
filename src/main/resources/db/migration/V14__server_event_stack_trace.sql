-- V14: сходжение схемы server_event к финальной (колонка stack_trace).
-- Часть баз успела применить раннюю редакцию V12 с колонкой detail; журнал —
-- расходные данные, поэтому таблица пересоздаётся целиком.
DROP TABLE IF EXISTS server_event;
CREATE TABLE server_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    level TEXT NOT NULL,
    logger TEXT NOT NULL,
    message TEXT NOT NULL,
    stack_trace TEXT
);

CREATE INDEX idx_server_event_ts ON server_event (ts DESC);
