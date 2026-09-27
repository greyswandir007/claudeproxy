# claudeproxy — план реализации

Статус: план согласован 2026-09-24; стек обновлён по решению от там же — реактивный.
Backend — Kotlin + Spring **WebFlux** (корутины + Flow), frontend — React + TypeScript,
БД — SQLite. **v1 готова — M1–M5 реализованы** (2026-09-24): конфигурация, реестр
моделей, auth по ключам из БД, `/v1/models`, pass-through Anthropic и полный перевод
Claude ↔ OpenAI, usage + окна, Stats API и управление ключами; дашборд React+TS
раздаётся бэкендом и встраивается в jar (buildDashboard); Basic Auth для production;
retention-очистка usage_event.

## 1. Что строим

Прокси-сервер, говорящий **полным протоколом Claude** (Anthropic Messages API), который:

- принимает запросы клиентов (Claude Code, SDK, любые Anthropic-совместимые клиенты)
  по собственным **ключам прокси**;
- принимает и **протокол OpenAI** (`POST /v1/chat/completions`, стриминг, tools):
  перевод OpenAI → Claude → провайдер → обратно; `GET /v1/models` — надмножество
  полей обоих протоколов;
- маршрутизирует каждый запрос по имени модели на один из **настроенных провайдеров**
  (свой эндпойнт, свой API-ключ, свой список моделей);
- представляет все модели всех провайдеров как **единое пространство моделей**;
- **переводит протокол** Claude ↔ OpenAI Chat Completions для OpenAI-совместимых
  провайдеров (OpenRouter, DeepSeek, vLLM, Ollama и т.п.) и делает прозрачный
  pass-through для Anthropic-совместимых;
- пишет **использование токенов** (input / output / cache_creation / cache_read)
  в SQLite по каждому запросу;
- отдаёт **дашборд**: токены за текущее 5-часовое окно, за неделю и за месяц —
  всего, по моделям, по провайдерам и по клиентским ключам;
- **генерирует и отзывает клиентские ключи** на странице «Ключи» дашборда
  (в БД — только SHA-256-хэши; YAML — сид стартовых ключей);
- **управляет провайдерами и моделями** из UI с хранением в БД
  (YAML — сид; изменения применяются сразу перезагрузкой реестра; у форм —
  базовый набор полей и расширенный, развёрнутый при редактировании);
- **информационные лимиты токенов провайдера** (5ч / неделя / месяц, любой
  набор): выработка видна на карточке провайдера («потрачено / лимит · %»),
  только в заданных категориях; 5-часовое окно провайдера живёт по тем же
  правилам «от первого обращения после простоя» (V3, provider_usage_window);
- **миграции БД** (db/migration/V*.sql, DatabaseMigrationRunner, транзакция на файл);
- **дискавери моделей провайдера** (GET /models у openai, /v1/models у anthropic)
  с выбором upstream-имени из списка при настройке моделей;
- **экран «Выдача моделей»**: что отдавать в GET /v1/models — секциями
  Claude/OpenAI, выбор провайдером или отдельными моделями (скрытые остаются
  маршрутизируемыми);
- **приоритеты и fallback**: public-имя может обслуживаться несколькими
  провайдерами (priority, меньше = выше); при повторимых ошибках
  (429 quota/5xx/сеть) — переключение на следующий маршрут, для стриминга —
  только до первого события клиенту; неудачные попытки пишутся в usage.

### Не-цели первой версии

- Балансировка/failover между провайдерами на одну и ту же модель
  (управление провайдерами и моделями из UI — реализовано).
- Балансировка/failover между провайдерами на одну и ту же модель.
- Batch API (`/v1/messages/batches`), Files API, MCP-коннекторы.
- Аутентификация дашборда (по умолчанию слушаем 127.0.0.1).

## 2. Ключевые решения

| Вопрос | Решение |
| --- | --- |
| Фреймворк | Spring Boot 4.1 **WebFlux** (Netty) + Kotlin 2.3 — реактивный сервер; вся логика на **корутинах и Flow** (`kotlinx-coroutines-reactor`), блокирующих вызовов в request-пути нет |
| Доступ к БД | Абстракция `DatabaseProvider` (интерфейс) + `SqliteDatabaseProvider`: SQLite + `spring-boot-starter-jdbc` + `JdbcTemplate`, все вызовы — через `execute { }` на диспетчере с параллелизмом 1 (SQLite — один писатель, R2DBC-драйвера production-качества для него нет). Схема — `schema.sql` (`CREATE TABLE IF NOT EXISTS`). При миграции на PostgreSQL — новая реализация интерфейса без смены вызывающего кода |
| БД | SQLite (xerial `sqlite-jdbc`), файл `data/claudeproxy.db`, все времена — epoch millis UTC |
| HTTP-клиент вверх | Spring **WebClient** (Reactor Netty): тело ответа — `Flow<DataBuffer>` / `Flow<String>` (SSE-строки) с полноценным backpressure; в коде — корутинные обёртки |
| JSON | Jackson + `jackson-module-kotlin` (в starter-webflux); перевод через JsonNode/деревья, без строгих DTO для чужих полей |
| Провайдеры | `anthropic` (pass-through) + `openai` (полный перевод протокола) |
| 5-часовое окно | **на каждый клиентский ключ** |
| Хранение клиентских ключей | В БД (`api_key`: SHA-256-хэш + префикс для отображения); генерация/отзыв — страница «Ключи» дашборда; YAML `api-keys` — только сид при старте |
| Логирование | **`kotlin-logging`** (`KotlinLogging.logger {}`) поверх SLF4J/Logback Spring Boot — единые уровни/формат через `logging.level.*`; ключи и тела запросов не логируются |
| Auth дашборда (production) | **Basic Auth** на `/api/**` и статику: лёгкий WebFilter с constant-time сравнением пароля (без Spring Security), включается заданием `claudeproxy.dashboard.auth.username/password`; `/v1/**` всегда — только api-ключи прокси |
| Конфиг | один YAML (`./config/application.yml`, Spring подхватывает автоматически), ключи — через `${ENV_VAR}`; реальный конфиг в .gitignore, в git — пример |

## 3. Архитектура

```text
Claude Code / SDK                     браузер
   │  Anthropic protocol                │  REST/JSON + static
   │  x-api-key: cpk_...                │
   ▼                                    ▼
┌──────────────────────────────────────────────────────────┐
│                    claudeproxy (Spring Boot)              │
│                                                          │
│  /v1/messages ─▶ auth ─▶ router ─▶ handler ─▶ usage ───┐ │
│  /v1/messages/count_tokens                              │ │
│  /v1/models                                             ▼ │
│  /api/**  ◀─ StatsService ◀────────────────────  SQLite   │
│  /        ◀─ static (web/dist — React dashboard)          │
└───────────┬──────────────────────────────┬───────────────┘
            │ pass-through                 │ перевод Claude↔OpenAI
            ▼                              ▼
   Anthropic-совместимые          OpenAI-совместимые
   (api.anthropic.com, релеи)     (OpenRouter, DeepSeek, vLLM…)
```

Поток запроса `/v1/messages`:

1. **Auth-фильтр**: `x-api-key` (или `Authorization: Bearer`) → SHA-256 →
   активный ключ в `api_key` (заодно обновляется `last_used_at`).
   Неизвестный/отозванный ключ → 401 в формате ошибок Anthropic.
2. **Роутер моделей**: `model` из тела → запись реестра моделей
   (провайдер + upstream-имя + флаги). Неизвестная модель → 404 `not_found_error`.
3. **Handler по типу провайдера** (suspend-функции; ответ клиенту — `Flow`):
   - `anthropic` — пересылка тела почти как есть (подмена модели и ключа),
     без буферизации: SSE проксируется чанками `Flow<DataBuffer>` с перехватом
     usage лёгким парсером событий;
   - `openai` — перевод запроса → вызов WebClient → перевод ответа (для
     стриминга — SSE state machine над `Flow<String>` по строкам потока).
4. **Usage-рекордер**: после завершения (успех/ошибка/обрыв) пишет строку в
   `usage_event` (fire-and-forget корутина на db-диспетчере) и поддерживает
   активное окно клиентского ключа.

Отдельно от `/v1/**`: дашборд и `/api/**` в production закрываются **Basic
Auth** (WebFilter, включается учётными данными в конфиге; браузер сам
запрашивает логин/пароль по `WWW-Authenticate`); `/v1/**` всегда авторизуется
только api-ключами прокси.

## 4. Конфигурация

Файл `./config/application.yml` (Spring Boot грузит его автоматически,
в git не попадает). Пример — `config/application.example.yml`.

```yaml
claudeproxy:
  listen-hint: 127.0.0.1          # информация для README; сам порт — в server.port
  window-hours: 5                 # длительность окна
  retention-days: 365             # автоочистка usage_event (0 = не чистить)

  # Basic Auth дашборда и /api — раскомментировать для production:
  # включается, когда заданы оба поля; без них дашборд только для localhost.
  # dashboard:
  #   auth:
  #     username: admin
  #     password: ${DASHBOARD_PASSWORD}

  api-keys:                       # сид-ключи: вносятся в БД при старте, если имени нет;
                                  # основное управление — страница «Ключи»
    - name: my-laptop
      key: ${PROXY_KEY_LAPTOP}    # cpk_...
    - name: work-desktop
      key: ${PROXY_KEY_DESKTOP}

  models:                         # настраиваемый список поддерживаемых моделей
    allow: []                     # пусто = все public-модели всех провайдеров;
                                 # иначе — белый список (алиасы/фильтр)

  providers:
    - name: anthropic-direct
      type: anthropic
      base-url: https://api.anthropic.com
      api-key: ${ANTHROPIC_API_KEY}
      models:
        - public: claude-opus-5          # имя, которое видит клиент
          upstream: claude-opus-5        # имя у провайдера

    - name: openrouter
      type: openai
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY}
      extra-headers:                     # опционально
        HTTP-Referer: https://my.setup
      models:
        - public: gpt-5.2
          upstream: openai/gpt-5.2
          reasoning: map                  # map | off: переводить ли thinking
          max-completion-param: false     # true => max_completion_tokens (o-серия)
```

Правила:

- подстановка `${ENV:default}` — средствами Spring, ключи в файле не храним;
- повторяющийся `public` у разных провайдеров — ошибка старта (в v1 нет балансировки);
- перезапуск для применения изменений (hot reload — не в v1);
- `api-keys` из YAML — только сид при старте; рабочий способ заведения ключей —
  генерация на странице «Ключи».

## 5. Протокол Claude со стороны клиента

| Метод и путь | Поведение |
| --- | --- |
| `POST /v1/messages` | основной эндпойнт, `stream: true/false` |
| `POST /v1/messages/count_tokens` | anthropic → pass-through; openai → оценка (~4 симв./токен + фикс. на картинку) |
| `GET /v1/models`, `GET /v1/models/{id}` | собирается из конфига (public-имена) |
| Заголовки | `x-api-key` **или** `Authorization: Bearer` — оба принимаются; `anthropic-version`, `anthropic-beta` — пробрасываются вверх (для anthropic-провайдеров) |

Учитываемые поля запроса: `model, messages, system, max_tokens, stream,
tools, tool_choice, temperature, top_p, top_k, stop_sequences, thinking,
output_config.effort, metadata, cache_control` в блоках.

SSE-события (полный набор, включая стриминг): `message_start`,
`content_block_start/delta/stop` (типы блоков `text`, `tool_use` с
`input_json_delta`, `thinking` с `signature_delta`), `message_delta`
(stop_reason + usage), `message_stop`, `ping`, `error`.

Ошибки — всегда в формате Anthropic:

```json
{"type":"error","error":{"type":"not_found_error","message":"..."}}
```

с корректными HTTP-кодами (401/404/400/429/500/529). Ответы 429/529 от
провайдера пробрасываются как есть, включая `retry-after`.

## 6. Маршрутизация моделей

- Реестр строится при старте: `public → (provider, upstream, flags)`.
- `GET /v1/models` и валидация запроса — по реестру (с учётом `models.allow`).
- Неизвестные клиенту поля запроса: пробрасываем для anthropic, логируем и
  отбрасываем для openai (политика «log-and-drop»).

## 7. Перевод Claude ↔ OpenAI

### 7.1 Запрос (Claude → OpenAI)

| Claude | OpenAI |
| --- | --- |
| `system` (строка/блоки; берём текст) | `messages[0] {role:"system"}` |
| блок `text` | часть `{type:"text"}` или строка |
| блок `image` (base64/url) | часть `image_url` (data-URI) |
| блок `tool_use` (assistant) | `tool_calls[]` (`arguments` = JSON.stringify(input)) |
| блок `tool_result` (user) | сообщение `{role:"tool", tool_call_id}`; `is_error` → префикс `[ERROR]` в контенте |
| блок `thinking` | отбрасывается (история рассуждений не ретранслируется) |
| `tools[]` (custom) | `[{type:"function", function:{name, description, parameters: input_schema}}]` |
| серверные тулзы (`web_search_*` и т.п.) | не переводятся → 400 с понятным сообщением |
| `tool_choice` `auto/any/tool` | `auto/required/{type:"function",…}`; `none` → убираем tools |
| `max_tokens` | `max_tokens` (или `max_completion_tokens` при флаге модели) |
| `stop_sequences` | `stop` |
| `temperature`, `top_p` | как есть; `top_k` — drop |
| `thinking`+`output_config.effort` | `reasoning_effort` (при `reasoning: map`, с обрезкой до поддерживаемых уровней) |
| `stream: true` | `stream: true` + `stream_options: {include_usage: true}` |
| `cache_control` | отбрасывается (кэш OpenAI не управляется с клиента) |

### 7.2 Ответ (OpenAI → Claude)

| OpenAI | Claude |
| --- | --- |
| `choices[0].message.content` | блоки `text` |
| `choices[0].message.tool_calls[]` | блоки `tool_use` (input = JSON.parse(arguments)) |
| `finish_reason`: `stop`/`tool_calls`/`length` | `stop_reason`: `end_turn`/`tool_use`/`max_tokens`; `content_filter` → `end_turn` + пометка в лог |
| `usage.prompt_tokens` / `completion_tokens` | `usage.input_tokens` / `output_tokens` |
| `usage.prompt_tokens_details.cached_tokens` | `usage.cache_read_input_tokens` |
| `id`, `model` | `id` (префикс `msg_`), публичное имя модели |

### 7.3 Стриминг — SSE state machine

Чанки OpenAI читаются из WebClient как `Flow<String>` (строки SSE); машина
состояний эмитирует события Claude наружу с сохранением порядка и backpressure.

| OpenAI-чанк | эмитируем Claude-событие |
| --- | --- |
| первый чанк | `message_start` (usage-заглушка; итог придёт в `message_delta`) |
| `delta.content` | `content_block_delta` `text_delta` |
| `delta.tool_calls[i]` (первые фрагменты) | `content_block_start` `tool_use` (id, name) |
| `delta.tool_calls[i].function.arguments` | `content_block_delta` `input_json_delta` |
| `delta.reasoning_content` / `delta.reasoning` (DeepSeek/OpenRouter) | блок `thinking` + `thinking_delta`, `signature` = `""` |
| `finish_reason` | закрываем блок, `message_delta` со `stop_reason` |
| финальный чанк с `usage` (include_usage) | `message_delta.usage` — полные input/output/cache_read |
| — | `message_stop`; `ping` каждые ~15 c простоя вверх |

Индексация блоков ведётся своей таблицей (OpenAI-индекс tool_calls ≠ индекс
контентных блоков Claude).

### 7.4 Флаги провайдера/модели

Разные OpenAI-совместимые провайдеры расходятся в деталях, поэтому в конфиге
на модель: `reasoning: map|off`, `max-completion-param: bool`. Неизвестные
поля ответов логируются (debug) — по факту работы с реальным провайдером
добавляем флаги, а не хардкод.

## 8. Учёт использования и 5-часовые окна

### Семантика окна (на каждый клиентский ключ)

- Окно = `[started_at, started_at + 5ч)`.
- При каждом учтённом запросе: ищем активное окно ключа (`ends_at > now`);
  если нет — создаём новое с `started_at = now`. Это и есть «отсчёт от первого
  использования после длительного времени».
- Токены простоя не начисляются: окно закрывается по времени, следующее
  открывается первым запросом.
- Дашборд: текущее окно (токены, осталось до сброса), история прошедших окон.

### Метрики

- **Всего / за сегодня / 7 дней / 30 дней**: суммы input/output/cache_creation/cache_read
  и число запросов; группировки: по модели, по провайдеру, по ключу.
- **Среднее за 5 часов** = `total_tokens(диапазон) / (часы_диапазона / 5)` —
  нормализованная скорость расхода; для текущего окна сравнивается с фактом.
- Timeline: бакеты по часу (7 дней) / по дню (30 дней), stacked
  input/output/cache_read.

### Запись usage

- anthropic: `message_start.usage` (input, cache) + `message_delta.usage` (output).
- openai: финальный usage-чанк / поле `usage` нестримового ответа.
- Обрыв стрима: пишем то, что успели получить, `status = aborted`; ошибки —
  `status` HTTP-код + `error`-текст. Запись — в `finally` по завершении запроса.

## 9. Схема БД (SQLite)

```sql
CREATE TABLE IF NOT EXISTS usage_event (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  ts                    INTEGER NOT NULL,            -- epoch millis UTC
  client_key            TEXT    NOT NULL,
  provider              TEXT    NOT NULL,
  model                 TEXT    NOT NULL,            -- public-имя
  upstream_model        TEXT,
  stream                INTEGER NOT NULL DEFAULT 0,
  input_tokens          INTEGER NOT NULL DEFAULT 0,
  output_tokens         INTEGER NOT NULL DEFAULT 0,
  cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
  duration_ms           INTEGER,
  status                INTEGER,                     -- HTTP-код; 0 = aborted
  error                 TEXT
);
CREATE INDEX IF NOT EXISTS idx_usage_event_ts        ON usage_event(ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_model_ts  ON usage_event(model, ts);
CREATE INDEX IF NOT EXISTS idx_usage_event_key_ts    ON usage_event(client_key, ts);

CREATE TABLE IF NOT EXISTS usage_window (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key TEXT    NOT NULL,
  started_at INTEGER NOT NULL,
  ends_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_window_key ON usage_window(client_key, ends_at);

CREATE TABLE IF NOT EXISTS api_key (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT    NOT NULL UNIQUE,
  key_hash     TEXT    NOT NULL UNIQUE,      -- SHA-256 (hex) полного ключа
  key_prefix   TEXT    NOT NULL,             -- cpk_+первые символы, для отображения
  created_at   INTEGER NOT NULL,
  revoked_at   INTEGER,                      -- NULL = активен
  last_used_at INTEGER
);
```

- Все агрегаты считаются SQL (`SUM`/`GROUP BY` по индексам) — объёмы личного
  прокси это позволяют, материализация не нужна.
- Очистка: `retention-days` — ежедневная задача удаляет старые `usage_event`.
- Клиентские ключи: `api_key`; сид из YAML вносится при старте (upsert по
  имени), `last_used_at` обновляется не чаще раза в минуту (троттлинг), чтобы
  не писать в БД на каждый запрос. Отзыв ключа не трогает историю usage.
- Директория `data/` в gitignore.

## 10. Stats API для фронтенда

| Эндпойнт | Возвращает |
| --- | --- |
| `GET /api/summary?range=window\|today\|7d\|30d&key=` | суммы токенов + число запросов |
| `GET /api/by-model?range=…` | таблица по моделям (токены, запросы, доля) |
| `GET /api/by-provider?range=…`, `GET /api/by-key?range=…` | аналогично |
| `GET /api/window?key=` | текущее окно ключа: границы, токены, время до сброса |
| `GET /api/windows?key=&limit=` | история окон |
| `GET /api/timeline?bucket=hour\|day&from=&to=` | серии для графика |
| `GET /api/config` | провайдеры и модели (read-only, без ключей) |
| `GET /api/keys` | список клиентских ключей (имя, префикс, даты, активность) |
| `POST /api/keys` | сгенерировать ключ (`{name}`); полный `cpk_…` возвращается ровно один раз |
| `POST /api/keys/{id}/revoke` | отозвать ключ (история usage сохраняется) |

CORS не нужен (раздаём с того же origin). Пагинации в v1 нет — фильтр по диапазону.

## 11. Фронтенд (React + TS)

- Vite + React + TypeScript + Recharts; каталог `web/`.
- Dev: `npm run dev` (Vite proxy → `localhost:8080/api`); Prod: `npm run build`
  → `web/dist` отдаётся Spring'ом как статика (встроенный jar — в M5).
- Экран **Dashboard** (одна страница, переключатель клиентского ключа):
  - карточка «Текущее окно 5ч»: прогресс-бар до конца окна, токены in/out/cache,
    сравнение со средним за 5ч;
  - карточки «Сегодня», «7 дней», «30 дней»: total + среднее за 5ч;
  - график-таймлайн (stacked area/bar: input / output / cache_read);
  - таблица по моделям (запросов, токены, доля %) и по провайдерам;
  - история окон (последние N, старт/конец/токены);
  - автообновление раз в 30 с + кнопка обновления.
- Отдельный экран «Модели и провайдеры» (read-only из `/api/config`).
- Экран **«Ключи»**: таблица ключей (имя, префикс, создан, последний запрос,
  статус), генерация нового (модалка: имя → полный `cpk_…` показывается
  **один раз**, кнопка «копировать»), отзыв с подтверждением.
- 401 от дашборда браузер сам обрабатывает диалогом Basic Auth — отдельный
  экран логина не нужен.

## 12. Структура репозитория

```text
claudeproxy/
├── PLAN.md, README.md, CLAUDE.md
├── build.gradle.kts, settings.gradle.kts, gradle/
├── scripts/                          # run-local / build-production (.bat для Windows, .sh для Linux)
├── config/
│   └── application.example.yml        # реальный config/application.yml — в gitignore
├── data/                              # SQLite (gitignore)
├── src/main/kotlin/ru/wizard/web/claudeproxy/
│   ├── ClaudeproxyApplication.kt
│   ├── config/ProxyProperties.kt      # @ConfigurationProperties("claudeproxy")
│   ├── auth/ApiKeyAuthFilter.kt        # WebFilter: x-api-key/Bearer → хэш → api_key
│   ├── auth/ApiKeyService.kt           # интерфейс: сид, аутентификация ключей
│   ├── auth/impl/JdbcApiKeyService.kt  # реализация на JdbcTemplate
│   ├── auth/BasicAuthWebFilter.kt      # Basic Auth дашборда и /api (production)
│   ├── routing/ModelRegistry.kt        # интерфейс реестра моделей
│   ├── routing/impl/DynamicModelRegistry.kt # снимок из БД, reload() после мутаций
│   ├── providers/ProviderModelService.kt    # CRUD провайдеров/моделей + сид из YAML
│   ├── providers/ProviderModelDiscoveryService.kt  # список моделей у провайдера
│   ├── providers/impl/JdbcProviderModelService.kt
│   ├── providers/impl/WebClientProviderModelDiscoveryService.kt
│   ├── db/DatabaseProvider.kt          # интерфейс доступа к БД (абстракция от СУБД)
│   ├── db/DatabaseMigrationRunner.kt   # миграции db/migration/V*.sql (транзакция на файл)
│   ├── db/impl/SqliteDatabaseProvider.kt     # SQLite: последовательный диспетчер
│   ├── proxy/UpstreamRetryPolicy.kt    # какие ошибки переключают маршрут (429/5xx/сеть)
│   ├── http/UpstreamWebClientConfiguration.kt  # общий WebClient провайдеров
│   ├── proxy/
│   │   ├── MessagesController.kt      # POST /v1/messages + /v1/messages/count_tokens
│   │   ├── ModelsController.kt        # GET /v1/models
│   │   ├── ApiError.kt, UpstreamError.kt, AnthropicErrors.kt
│   │   ├── UsageAccumulator.kt, SseUsageSniffer.kt
│   │   ├── AnthropicHandler.kt        # интерфейс pass-through + перехват usage
│   │   ├── impl/WebClientAnthropicHandler.kt  # реализация на WebClient
│   │   ├── openai/OpenAiHandler.kt    # интерфейс перевода протокола (M2)
│   │   └── openai/impl/               # трансляторы запроса/ответа/SSE (M2)
│   ├── usage/
│   │   ├── UsageRecorder.kt           # интерфейс: запись события
│   │   ├── WindowService.kt           # интерфейс: 5-часовые окна
│   │   ├── impl/AsyncUsageRecorder.kt # fire-and-forget корутина
│   │   ├── impl/JdbcWindowService.kt
│   │   └── StatsService.kt            # SQL-агрегаты для /api
│   ├── api/StatsController.kt         # /api/**: агрегаты статистики
│   ├── api/KeyController.kt           # /api/keys: генерация/список/отзыв
│   └── api/ProvidersController.kt     # /api/providers|models: CRUD провайдеров и моделей
├── src/main/resources/
│   ├── application.yml                # дефолты (порт, datasource, кодек-лимиты)
│   └── db/migration/                  # V1__initial.sql, V2__priority_exposure_and_fallback.sql, …
└── web/                               # React TS (Vite)
    ├── package.json, vite.config.ts, tsconfig.json
    └── src/{App.tsx, api/, pages/, components/}
```

## 13. Этапы

| Этап | Содержание | Критерий готовности |
| --- | --- | --- |
| **M0** (этот шаг) | PLAN.md, README.md, .gitignore, пример конфига, git init | файлы готовы |
| **M1** ✅ | Зависимости WebFlux/корутин (уже в build), конфигурация + реестр моделей, auth-фильтр (WebFilter), `/v1/models`, pass-through anthropic (stream и не-stream), SQLite + запись usage + окна | Claude Code через прокси работает с anthropic-провайдером, в БД падают события |
| **M2** ✅ | OpenAI-перевод: не-stream, затем stream + tools + reasoning, count_tokens | Claude Code полноценно работает с OpenAI-провайдером (инструменты, стриминг) |
| **M3** ✅ | Stats API (`/api`) со всеми агрегатами; управление ключами (`/api/keys`): генерация, список, отзыв, сид из YAML | curl'ом получаем summary/by-model/timeline/windows; сгенерированный ключ проходит auth |
| **M4** ✅ | Фронтенд-дашборд + экран «Ключи» | Экран показывает окна/таблицы/график, автообновление; ключ генерируется из UI и работает |
| **M5** ✅ | Полировка: сборка `web/dist` в jar, логирование (kotlin-logging), Basic Auth дашборда для production, retention, обработка обрывов, README-финал | Одна команда запуска, всё работает end-to-end; дашборд за Basic Auth |

## 14. Риски и противоядия

- **Разнобой OpenAI-совместимых провайдеров** (`reasoning_content` vs
  `reasoning`, `max_completion_tokens`, форма чанков tool_calls) → флаги в
  конфиге на модель + debug-лог непереведённых полей; расширяем по мере
  подключения реальных провайдеров.
- **Буферизация/таймауты SSE** → Netty отдаёт чанки по мере поступления из
  `Flow` без агрегации; response-timeout WebClient вверх ставим щедрым,
  ping-события поддерживают соединение при простое провайдера.
- **Отмена со стороны клиента** → отмена корутины запроса каскадом закрывает
  upstream-соединение (WebClient); usage записываем в `finally`/`onCompletion`.
- **Большие тела** (Claude Code шлёт мегабайты контекста) → читаем реактивно
  (`bodyToMono`/DataBuffer без лишних копий); лимиты Netty на большой запрос
  проверяем.
- **Обрыв стрима** → запись usage в `finally`, статус `aborted`, «недополученный»
  usage не теряется частично полученный.
- **Таймзоны** → везде epoch millis UTC, локаль — только на фронтенде.
- **Windows-окружение** → пути к SQLite относительные, файлы БД в `data/`,
  антивирус может блокировать свежий `.db` (учтено в README).

## 15. Безопасность

- Ключи провайдеров и клиентские ключи — только через `${ENV}`;
  `config/application.yml` и `data/` в gitignore.
- Ключи хранятся в БД только как SHA-256-хэши (префикс — для отображения в UI);
  генерация — SecureRandom (`cpk_` + 43 символа base64url ≈ 256 бит); сравнение
  по хэшу; полный ключ показывается один раз при создании; ключи не логируются.
- **Production**: дашборд и `/api/**` закрываются **Basic Auth**
  (`claudeproxy.dashboard.auth.username/password`; WebFilter без Spring
  Security, сравнение пароля constant-time, `WWW-Authenticate: Basic`).
  Пока учётные данные не заданы, прокси обязан слушать только 127.0.0.1.
  Эндпоинты `/v1/**` под Basic Auth не ставятся — там api-ключи прокси.
  Пароль задаётся через `${ENV}`, в конфиге/логах не хранится и не пишется.
- Прокси по умолчанию слушает `127.0.0.1`; вынос в сеть — осознанное действие
  пользователя (в README), TLS терминируется снаружи.
- Дашборд без auth в v1 (только localhost); опция токена — в бэклоге.

## 16. Бэклог (предложения на будущее)

Согласован 2026-09-25. Приоритеты — от реального использования (личный прокси,
Claude Code — основной клиент).

### Быстрые победы (малый объём, заметная польза)

1. **Кулдаун неудачных маршрутов (circuit breaker)** — главная дыра текущего
   fallback: если у приоритетного провайдера исчерпана квота, каждый запрос всё
   равно сначала бьётся в него (полный round-trip ошибки) и только потом
   переключается. После 429/5xx маршрут уходит в кулдаун на N секунд
   (или на `retry-after` из ответа), реестр это учитывает, состояние видно
   на дашборде.
2. **Fallback-попытки и латентность на дашборде** — данные уже пишутся
   (`usage_event.status/error/duration_ms`): список переключений
   «primary → secondary» за период, p50/p95 латентности по моделям
   и провайдерам, доля ошибок.
3. **Учёт `retry-after` при 429** — часть п.1: длительность кулдауна — из
   заголовка провайдера, когда он есть.
4. **Разбивка текущего 5-часового окна по провайдерам с независимым отсчётом** —
   у каждого провайдера своё окно: старт вычисляется отдельно от первого
   обращения после простоя (данные уже частично есть: `provider_usage_window`
   и `/api/provider-limits` с границами окна провайдера), нужно показать на
   дашборде для каждого провайдера своё начало окна, время до сброса и
   выработку — независимо от лимитов и от окна клиентского ключа.
   В «Истории окон» — колонка с провайдером-владельцем окна: показывать и
   окна провайдеров (из `provider_usage_window`) рядом с окнами клиентского
   ключа, а в окнах ключа — перечень провайдеров, обслуживших запросы этого
   окна (выработка из usage_event по границам окна).
5. **Графики: линии вместо заливки + интерактивная легенда** — перевести
   таймлайны (общий и срезы) с AreaChart с заливкой на LineChart (линии
   без закраски области, 2px, точки-маркеры на hover); легенда
   интерактивная: наведение на элемент подсвечивает только его серию,
   остальные затемняются, клик — изолирует/возвращает; на срез-таймлайне
   с многими сериями это критично для читаемости. Реализация: Recharts
   custom legend (onMouseEnter → state hoveredSeries →
   strokeOpacity/fillOpacity по сериям), activeDot для маркеров.

### Средние фичи

1. **Лимиты на клиентский ключ** — allowlist моделей для ключа (ключ «ноутбук» →
   только эти модели) и/или квота токенов на окно/день с ошибкой
   `rate_limit_error`; настоящая мультитенантность.
2. **Оценка стоимости** — цена ($/1M токенов) на модель в БД, вводится в UI;
   колонка «≈$» везде, где токены.
3. **PostgreSQL-реализация DatabaseProvider** — архитектура готова (интерфейс +
   миграции совместимы); R2DBC/JDBC-провайдер для серверного развертывания.
4. **Docker + docker-compose** — образ с jar, том для SQLite, пример
   с PostgreSQL.
5. **Кэш повторяющихся запросов** — экономия токенов на идентичных запросах:
   канонический ключ = хэш (модель + system + messages + tools + параметры
   сэмплинга; `stream` и `metadata` в ключ не входят), ответ сохраняется
   в SQLite (`request_cache`: тело + usage + TTL) и отдаётся без похода
   к провайдеру. Для stream-клиентов (Claude Code) — синтез SSE-событий
   из сохранённого ответа. Вкл/выкл и TTL per provider (каталог оверрайдов,
   REQUEST_CACHE_TTL_MS, 0 = выключен); single-flight на один ключ против
   дребезга; лимит записей + LRU-вытеснение; в usage_event пишется событие
   с provider=`cache` и saved_tokens = полный объём закэшированного ответа —
   на дашборде экономия видна рядом с обрезкой. Честная оговорка: в агентных
   сессиях запросы почти не повторяются (контекст растёт), главный выигрыш —
   веб-чат, программные клиенты, повторы одинаковых промптов.
   (Реализовано в M16, миграция V10; кросс-режимные повторы конвертируются
   JSON ↔ SSE, экономия видна в метрике M11.)

### Крупные / на вырост

1. **Sticky-аффинность маршрутов** — разговор держать на одном провайдере,
   пока жив (хэш начала разговора → выбор маршрута), чтобы не убивать
   промпт-кэш провайдера переключениями.
2. **Балансировка равноприоритетных маршрутов** — round-robin между маршрутами
   с одинаковым priority.
3. **Batches API** (`/v1/messages/batches`) — pass-through для
   anthropic-провайдеров, фоновые задачи вдвое дешевле.
4. **Точный count_tokens для openai** — калибровка коэффициента «символы →
   токены» по фактическим `input_tokens` из ответов (самообучение в БД).
5. **Инженерное** — юнит-тесты трансляторов (сейчас только интеграционные),
   CI (GitHub Actions), JSON-структурированные логи, бэкап-задача для SQLite.

### Рекомендуемый следующий шаг

Реализованы: все «быстрые победы» (M6 + M14), средние п.1–2 (квоты ключей
M15п1, тарификация M15п2) и п.5 (кэш повторов, M16).

Следующий рекомендуемый этап — **раздел 18: правки по итогам эксплуатации
(M17 → M18)**; «средние п.3 + п.4: PostgreSQL + Docker» из этого списка —
после них.

## 17. План v2 (поэтапный, согласован 2026-09-25)

16 пунктов заказчика сгруппированы в этапы по зависимостям и объёму.
Нумерация пунктов — по исходному списку заказчика. M6 (кулдаун маршрутов)
из бэклога остаётся самостоятельным, может идти между этапами.

### Этап 1 — M7 «Лимиты: UX и достоверность» (S, пункты 1, 2, 4, 5, 6)

- п.2: ввод/отображение лимитов **в миллионах токенов** (например «5,5» =
  5 500 000; хранение в БД — в токенах, как сейчас);
- п.1: разобрать, почему шкалы не заполняются: (а) при лимитах в сырых
  единицах процент округлялся до 0 — уходит с п.2; (б) окно провайдера
  стартует только с первого запроса после деплоя — до этого выработка окна 0,
  задокументировать; (в) минимальная «полоска» ≥2% при spent>0; покрыть
  тестами, включая границы окна;
- п.6: производный лимит для отображения, если категория не задана: 5ч из
  месячного (мес/144), иначе из недельного (нед/33,6); неделя из месячного
  (мес/4,29). Приоритет источников: месяц → неделя → 5ч; пометка
  `derived: true` в API, в БД не сохраняется;
- п.4: отступ «Истории окон» от секции лимитов (вёрстка);
- п.5: явная легенда таймлайна (цвета серий), включая ч/б-читаемость.

Готовность: лимит «5,5» млн показывает заполнение и производные категории,
вёрстка ровная, легенда читается.

### Этап 2 — M8 «Статистика: срезы и навигация» (M, пункты 3, 7, 8, 9, 10)

- п.3: переключатель среза таймлайна **общее / по моделям / по провайдерам**
  (`/api/timeline?group=total|model|provider`, серии = топ-N + прочее);
- п.7: режим «один день по часам» с **вертикальными границами 5-часовых окон**
  выбранного ключа (ReferenceLine);
- п.8: **навигация по периодам**: пресеты (сегодня/вчера/позавчера/7д/прошлая
  неделя/30д/прошлый месяц) + произвольный from/to; кастомные диапазоны
  поддержать и в by-model/by-provider/window-выработке;
- п.9: таблицы «По моделям / По провайдерам» — колонки **за 7д и за 30д**
  (и текущий период навигации);
- п.10: карточка текущего окна — **лимиты провайдеров, релевантные окну**
  (у кого задан 5ч-лимит) + срез расхода по моделям/провайдерам внутри окна.

Готовность: срезы переключаются, день с окнами читается, вчера/прошлая
неделя/произвольный период работают во всех виджетах.

### Этап 3 — M9 «Настройки поведения провайдера» (M, пункты 11, 12)

- п.11: **маппер effort-уровней** per provider: вход канонический
  low/medium/high/xhigh/max (+синонимы middle→medium, ultra→max), значение —
  строка провайдера; по умолчанию выключен (проброс как есть). Хранение в
  provider (V4), применение в обоих трансляторах (openai reasoning_effort,
  anthropic output_config.effort);
- п.12: **оверрайды входных параметров** per provider из каталога (выбор
  из списка, добавленные скрываются): API_TIMEOUT_MS (таймаут вызова
  провайдера), MAX_OUTPUT_TOKENS (cap max_tokens), MAX_INPUT_TOKENS
  (guard с внятной 4xx), TEMPERATURE_OVERRIDE, TOP_P_OVERRIDE,
  FORCED_REASONING_EFFORT (после маппера), DISABLE_THINKING,
  EXTRA_STOP_SEQUENCE. Каталог — код (id, тип, описание), значения —
  provider_setting (V4). CLAUDE_CODE_* — клиентские переменные, их в каталог
  не включаем, компенсируем прокси-аналогами.

Готовность: маппер и оверрайды настраиваются в расширенном наборе,
применяются к запросам, покрыты тестами трансляции.

### Этап 4 — M10 «OAuth-провайдеры» (L, пункт 13)

- auth-тип провайдера: api-key | oauth (client_id/secret, token_url, scopes,
  refresh); хранение токенов в БД (зашифровано ключом из ENV), фоновый
  refresh, подстановка Bearer в вызовы; UI-тип «OAuth» в форме провайдера.

### Этап 5 — M11 «Экономия токенов» (L, пункт 14)

- Инструменты (уровни, вкл/выкл per provider): cache-control инъекция /
  prompt_cache_key для openai-провайдеров; headroom-бюджет (обрезка старых
  tool_result/attachments при приближении к MAX_INPUT_TOKENS); дедупликация
  system-промпта. Метрика «сэкономлено» на дашборде: кэш-чтения и обрезанные
  токены против сценария без оптимизаций.

### Этап 6 — M12 «Веб-чат в дашборде» (M-L, пункт 15)

- Страница «Чат»: выбор клиентского ключа, диалог через собственный
  /v1/messages (SSE в браузер), история chat_message в БД на ключ,
  сброс/переименование треда; стриминг и tool-вызовы — только текст.

### Этап 7 — M13 «Автороутер моделей» (XL, опционально, пункт 16)

- Классификатор входящего запроса (правила + при необходимости дешёвая
  модель) → выбор подходящей модели; матрица «тип задачи → модели» в UI.

Порядок: M7 → M8 → M9 → M10 → M12 → M11 → M13 (чат раньше экономии —
даёт живой трафик для замеров экономии; M6 втягивается по ходу M9).

## 18. План v2.1 — правки по итогам эксплуатации (согласован 2026-09-27)

5 пунктов заказчика по итогам живого использования. Нумерация пунктов — по
исходному списку заказчика.

### Этап 1 — M17 «Дашборд: обновление графика и расшифровка ошибок» (M, пункты 1, 2)

- п.1: график расхода по времени не обновляется автоматически. По коду
  `/api/timeline` перезапрашивается каждые 30 с вместе с остальными данными
  (refreshTick в DashboardPage), то есть опрос есть — воспроизвести и найти
  реальную причину (кандидаты: HTTP-кэш браузера — добавить
  `Cache-Control: no-store` на `/api/*`, фиксированная ось X/гранулярность
  бакета, стейт графика); починить и проверить на живом трафике.
- п.2: в ошибках маршрутизации видно только короткое сообщение. Сейчас в
  `usage_event.error` пишется обрезка до 300 символов (`shortError`), а
  полное тело ответа провайдера (`UpstreamError.upstreamBody`) на стрим-путях
  вообще выбрасывается. Хранить полную расшифровку (тело ответа провайдера,
  до ~64 КБ) в отдельной колонке `usage_event` (миграция БД); в списке
  ошибок — короткий текст, по клику — полная расшифровка (эндпоинт по id
  события, на фронте — раскрытие по кнопке).

Готовность: график обновляется без перезагрузки страницы; клик по ошибке
маршрутизации показывает полный ответ провайдера.

### Этап 2 — M18 «Окна без лимитов и диагностика кэша повторов» (S–M, пункты 3, 4)

- п.3: не создавать и не вести 5-часовые окна для провайдеров без лимитов.
  Сейчас окно создаётся при каждой записи usage (AsyncUsageRecorder) для
  любого провайдера, кроме `cache`. Гейт: окно заводится/продлевается только
  при наличии хотя бы одного лимита у провайдера. Дорожки таких провайдеров
  уйдут с графика окон и карточки текущих окон — ожидаемо; исторические
  строки не трогаем.
- п.4: «кэш не работает, ни разу не видел использование». Ключ кэша —
  SHA-256 полного канонического тела запроса (без `stream`/`metadata`), а
  точные повторы в трафике Claude Code редки — вероятно, попаданий фактически
  нет, и это не баг. Проверить тестом и ручным повтором идентичного запроса;
  добавить счётчики lookups/hits/misses и причину промаха в API и на
  дашборд, debug-лог. По итогам решить, достаточно ли точного ключа
  (смягчение ключа — отдельное обсуждение, в этап не входит).

Готовность: провайдеры без лимитов не заводят окна; по кэшу видно, сколько
было lookups/hits и почему промахи.

### Этап 3 — M19 «Локальная модель-оптимизатор» (L–XL, опционально, пункт 5)

- Включаемая опция: локальная модель за OpenAI-совместимым эндпоинтом
  (base URL + имя модели в настройках прокси). Применения для экономии
  токенов: (а) осмысленное сжатие старых tool_result вместо байтовой обрезки
  (M11); (б) классификатор автороутера (M13) без оплаты облачной модели;
  (в) выбор точек кэширования. Ограничители: строгие таймауты, fallback на
  обычное поведение при недоступности модели, метрика сэкономленных токенов.
  До старта уточнить у заказчика сценарии и модель.

Порядок: M17 → M18 → M19 (M19 опционален; PostgreSQL + Docker из
раздела 16 — после M17–M18).
