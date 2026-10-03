# claudeproxy

Локальный шлюз (Kotlin, Spring WebFlux) между Claude-клиентами и
LLM-провайдерами: подменяет `api.anthropic.com` для Anthropic-совместимых
провайдеров и переводит протокол для OpenAI-совместимых, ведёт учёт токенов
и 5-часовых окон «как у подписки Claude», ретраит/переключает провайдеров и
отдаёт дашборд для управления.

Подробности: [docs/architecture.md](docs/architecture.md) — устройство;
[docs/settings.md](docs/settings.md) — справочник настроек;
[docs/api.md](docs/api.md) — эндпоинты. Внутренний журнал разработки — PLAN.md.

## Возможности

- **Прозрачное проксирование Claude API**: `/v1/messages` (SSE и JSON,
  tool calls, thinking, beta-заголовки), `/v1/messages/count_tokens`,
  `/v1/models`, Batches API, файлы.
- **Перевод Claude ↔ OpenAI**: OpenAI-провайдеры работают через те же
  публичные модели, включая стриминг и tool calls; входящая
  OpenAI-совместимость — `POST /v1/chat/completions` для любых моделей.
- **Маршрутизация и отказоустойчивость**: приоритеты + round-robin,
  ретраи и fallback на следующий провайдер, кулдауны, sticky-аффинность
  разговоров (сохранение промпт-кэша апстрима).
- **Клиентские ключи и окна**: ключи прокси с SHA-256-хэшами, квоты
  (окно/неделя/30 дней), 5-часовые окна как у подписки Claude.
- **Учёт и аналитика**: usage по моделям/провайдерам/ключам, таймлайн,
  задержки, стоимость (per_million / monthly), детали ошибок.
- **Кэш запросов**: идемпотентные запросы по SHA-256 канонического тела;
  стриминговые ответы воспроизводятся из кэша (TTL 5 мин / 1 ч).
- **Оптимизатор токенов** (M30): модель-сжатие старых `tool_result`
  с кэшем, breaker'ом и статистикой экономии.
- **Прокси-эндпоинты** (M31): исходящие вызовы провайдера — через
  HTTP(S)-CONNECT или SOCKS4/5, с проверкой связности из UI.
- **Калибровка count_tokens** для openai-провайдеров по фактическим ответам.
- **Дашборд** (React): модели/провайдеры, ключи, окна, события сервера,
  встроенный чат-плейграунд; Basic Auth по желанию.
- **Хранилище**: SQLite (файл, снапшот-бэкапы по cron) или PostgreSQL.

## Быстрый старт

Требуется JDK 25. Сборка и запуск в dev-режиме:

```bash
./gradlew :app:bootRun          # http://localhost:8080, SQLite data/claudeproxy.db
```

Прод (`scripts/`):

```bash
scripts/build            # чистая сборка + bootJar + копирование в app/build/libs
scripts/build-local      # то же + jar с запечённым портом 9090 (scripts/run-local)
```

Первичная настройка — в дашборде: создать провайдера (тип, base URL,
api-ключ), добавить модель с публичным именем, создать ключ клиента
(секрет показывается один раз).

### Подключение клиентов

```bash
# Claude Code
export ANTHROPIC_BASE_URL=http://localhost:8080
export ANTHROPIC_AUTH_TOKEN=cpk_...        # или ANTHROPIC_API_KEY

# OpenAI SDK (входящая совместимость)
client.base_url = "http://localhost:8080/v1"
client.api_key  = "cpk_..."
```

curl-проверка:

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: cpk_..." -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-4-5","max_tokens":64,"messages":[{"role":"user","content":"привет"}]}'
```

## Прод-заметки

- Слушать наружу — только вместе с Basic Auth дашборда
  (`claudeproxy.dashboard.auth`, см. [docs/settings.md](docs/settings.md)).
- Клиентские ключи хранятся хэшами SHA-256 и не логируются; секреты
  провайдеров можно держать в `${ENV_VAR}`-ссылках.
- Логи — по умолчанию человекочитаемые; `SPRING_PROFILES_ACTIVE=json`
  переключает в построчный JSON. WARN/ERROR дополнительно попадают в
  журнал событий дашборда.
- Бэкапы SQLite — `claudeproxy.backup.*` (по умолчанию выключено);
  для PostgreSQL используйте `pg_dump`.
- Миграции применяются при старте; наборы по диалектам —
  `database/src/main/resources/db/migration/{sqlite,postgres}/`.

## Каталоги и файлы

| Путь | Назначение |
| --- | --- |
| `app/build/libs/claudeproxy-*.jar` | собранный fat-jar |
| `data/claudeproxy.db` | SQLite по умолчанию |
| `config/application.example.yml` | аннотированный пример настроек |
| `database/src/main/resources/db/migration/` | SQL-миграции (по диалектам) |
| `web/` | исходники дашборда (React + TS + Vite) |
| `scripts/` | bat/sh-скрипты сборки и запуска |
| `docs/` | документация (этот набор) |

## Разработка

`./gradlew build` — вся сборка и тесты (модульные + интеграционные,
включая тестовые серверы на reactor-netty; тестовые БД изолированы в
`build/test`). Дашборд: `cd web && npm install && npm run dev` (dev-сервер
Vite проксирует `/api` на бэкенд). Кодстайл — см. CLAUDE.md.
