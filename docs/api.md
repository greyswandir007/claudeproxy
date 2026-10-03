# HTTP API claudeproxy

Прокси выставляет два семейства эндпоинтов: **клиентское** `/v1/*`
(протоколы Claude и OpenAI — для SDK и Claude Code) и **дашбордное**
`/api/*` (управление и статистика). Описание архитектуры — [architecture.md](architecture.md).

## Аутентификация

| Семейство | Способ |
| --- | --- |
| `/v1/*` | всегда ключ прокси: заголовок `x-api-key: cpk_...` или `Authorization: Bearer cpk_...` |
| `/api/*` | без авторизации, пока Basic Auth не настроен (`claudeproxy.dashboard.auth`) |

Ошибки `/v1/*` возвращаются в формате Anthropic
(`{"type":"error","error":{"type","message"}}`); для
`/v1/chat/completions` — в формате OpenAI (`{"error":{...}}`).
Ретраебельные 429/529 могут содержать `retry-after`.

## Клиентские эндпоинты

### Протокол Claude

| Метод и путь | Назначение |
| --- | --- |
| `POST /v1/messages` | главный эндпоинт: стриминг (SSE) и обычные ответы; tool calls, thinking, `anthropic-beta` |
| `POST /v1/messages/count_tokens` | подсчёт токенов (кэш запросов + оценка; openai — калибровка) |
| `GET /v1/models`, `GET /v1/models/{id}` | публично видимые модели (формат надмножество Anthropic/OpenAI) |
| `POST /v1/messages/batches` · `GET /v1/messages/batches` · `GET .../{batchId}` · `POST .../{batchId}/cancel` · `POST .../{batchId}/results` | Batches API, pass-through для anthropic-провайдеров |

### Файлы

`POST /v1/files` (multipart), `GET /v1/files`, `GET /v1/files/{fileId}`,
`GET /v1/files/{fileId}/content`, `DELETE /v1/files/{fileId}` —
pass-through без разбора содержимого; `file_id` в батчах проходит как есть.

### Протокол OpenAI (входящая совместимость)

`POST /v1/chat/completions` — стриминг и обычные ответы; доступны все
модели прокси независимо от типа провайдера. `GET /v1/models` читается
OpenAI SDK без адаптаций.

## Эндпоинты дашборда `/api/*`

Все возвращают/принимают JSON. Изменения применяются сразу (реестр
маршрутов перезагружается после каждой правки).

### Ключи и доступ

| Метод и путь | Назначение |
| --- | --- |
| `GET/POST /api/keys`, `PUT /api/keys/{id}`, `POST /api/keys/{id}/revoke` | клиентские ключи: список, создание (полный секрет — только в ответе создания), квоты/модели, отзыв |
| `GET/POST /thread`, `DELETE /messages`, `GET /state`, `POST /send` | встроенный чат-плейграунд (тред, история, NDJSON-стрим) |

### Провайдеры и модели

| Метод и путь | Назначение |
| --- | --- |
| `GET/POST /api/providers`, `PUT/DELETE /api/providers/{id}` | CRUD провайдеров |
| `POST /api/providers/discover-models` | запрос списка моделей у провайдера |
| `PUT /api/providers/{id}/exposure` | видимость провайдера в `/v1/models` |
| `POST /api/providers/{id}/models`, `PUT/DELETE /api/models/{id}` | CRUD моделей |
| `PUT /api/models/{id}/exposure` | видимость модели |
| `GET/POST/PUT/DELETE /api/proxies`, `POST /api/proxies/{id}/check` | прокси-эндпоинты (M31) и проверка связности |

### Статистика и диагностика

| Метод и путь | Назначение |
| --- | --- |
| `GET /api/summary`, `/api/by-model`, `/api/by-provider`, `/api/by-key` | сводка и группировки использования за период |
| `GET /api/window`, `/api/windows`, `/api/provider-windows` | текущее 5-часовое окно и история окон |
| `GET /api/window-boundaries`, `/api/provider-window-boundaries` | границы окон (синхронизация карточек UI) |
| `GET /api/timeline`, `/api/latency` | таймлайн запросов и перцентили задержки |
| `GET /api/provider-limits`, `/api/provider-costs` | выработка лимитов и расчёт стоимости |
| `GET /api/fallback-report`, `/api/route-cooldowns` | резервные переключения и кулдауны |
| `GET /api/request-cache-stats`, `/api/conversation-affinity-stats` | диагностика кэша запросов и sticky-аффинности |
| `GET /api/stats/errors/{eventId}` | детали ошибки по usage-событию |
| `GET /api/config` | эффективная конфигурация `claudeproxy.*` |

### Оптимизатор, калибровка, события

| Метод и путь | Назначение |
| --- | --- |
| `GET /api/optimizer/stats`, `GET/PUT /api/optimizer/config` | статистика и конфиг модель-оптимизатора (M30) |
| `GET/DELETE /api/token-calibration` | записи калибровки count_tokens и сброс |
| `GET /api/server-events`, `DELETE /api/server-events` | журнал событий сервера (фильтры, курсор) и очистка |
