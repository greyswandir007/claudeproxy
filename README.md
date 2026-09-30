# claudeproxy

Локальный прокси, говорящий **полным протоколом Claude** (Anthropic Messages API)
и маршрутизирующий запросы в **несколько провайдеров** — Anthropic-совместимые
(прозрачный pass-through) и OpenAI-совместимые (OpenRouter, DeepSeek, vLLM,
Ollama… с полным переводом протокола, включая стриминг и tool calls).

Все модели всех провайдеров предъявляются клиенту как **единое пространство
моделей**: клиент (например, Claude Code) подключается одним API-ключом прокси
и запрашивает любую из настроенных моделей.

## Возможности

- **Полный протокол Claude**: `POST /v1/messages` (стриминг SSE и обычные
  ответы), `POST /v1/messages/count_tokens`, `GET /v1/models`; tool calls,
  thinking-блоки, проброс `anthropic-beta`-заголовков, ошибки в формате
  Anthropic (включая 429/529 с `retry-after`).
- **Несколько провайдеров**: у каждого свой эндпойнт, API-ключ и список
  моделей с маппингом «публичное имя → upstream-имя».
- **Управление провайдерами и моделями из UI**: добавление/редактирование/
  удаление с хранением в БД, изменения применяются сразу (реестр перезагружается
  после каждой правки). Формы — с базовым набором полей и расширенным
  (reasoning/max-completion/приоритет для моделей, extra-заголовки для провайдеров),
  при редактировании расширенный набор показывается развёрнутым.
  YAML-конфиг остаётся сидом при старте; api-ключ провайдера можно задать
  литералом или ссылкой `${ENV_VAR}`.
- **Лимиты токенов провайдера**: информационные лимиты на 5 часов / неделю /
  месяц (любой набор) с выработкой на карточке провайдера — прогресс-бар
  «потрачено / лимит · %» только в заданных категориях.
- **Дискавери моделей**: при настройке провайдера можно запросить список его
  моделей (GET /models / /v1/models) и выбирать upstream-имя из списка.
- **Экран «Выдача моделей»**: что отдавать клиентам в GET /v1/models —
  секциями Claude/OpenAI, выбор целиком провайдера или отдельных моделей;
  скрытые модели остаются доступными по прямому запросу.
- **Приоритеты и fallback**: одну и ту же модель можно подключить от нескольких
  провайдеров с приоритетами; при quota exceeded / service unavailable и других
  повторимых ошибках прокси переключается на следующий маршрут (для стриминга —
  до первого события клиенту), попытки фиксируются в статистике. Маршруты с
  одинаковым приоритетом чередуются (round-robin), размазывая нагрузку и квоты
  между равнозначными каналами.
- **Sticky-аффинность разговоров**: разговор (последовательность запросов с общим
  началом — system + tools + первое сообщение) продолжается на том же провайдере
  из числа равнозначных, чтобы не терять промпт-кэш вверх по течению; новые
  разговоры по-прежнему чередуются round-robin, строгий приоритет доминирует над
  привязкой, при фейловере разговор перепривязывается на запасной канал.
  Привязки in-memory с TTL, включается настройкой
  `claudeproxy.conversation-affinity.enabled=true` (по умолчанию выключено; TTL —
  `ttl-seconds`, лимит записей — `max-entries`), диагностика —
  `GET /api/conversation-affinity-stats`. Компакция истории или смена tools
  меняют ключ — разговор привяжется заново.
- **Калибровка count_tokens для openai**: у openai-провайдеров нет аналога
  `/count_tokens`, и прокси оценивает токены по правилу «~4 симв./токен +
  1600 на картинку». Коэффициент «символы → токены» дообучается по
  фактическим `input_tokens` ответов (таблица `token_calibration`,
  накопительные суммы по паре модель/провайдер; картинки вычитаются
  фиксированной оценкой) и применяется в `POST /v1/messages/count_tokens`,
  пока образцов меньше минимума — прежнее правило. Включено по умолчанию
  (`claudeproxy.token-calibration.enabled`, минимум образцов —
  `minimum-samples`), диагностика и сброс —
  `GET`/`DELETE /api/token-calibration`.
- **Миграции БД**: db/migration/{sqlite,postgres}/V*.sql применяются
  автоматически при старте, каждая в транзакции; набор диалекта выбирается по
  JDBC-URL источника данных (DatabaseMigrationRunner + DatabaseDialect).
- **Собственные ключи доступа**: клиенты авторизуются ключами прокси
  (`x-api-key` или `Authorization: Bearer`), ключи провайдеров наружу не
  выходят; ключи генерируются и отзываются на странице «Ключи» дашборда
  (в БД — только SHA-256-хэши, полный ключ показывается один раз).
- **Статистика использования (SQLite или PostgreSQL)**: токены input / output /
  cache_creation / cache_read по каждому запросу; всего, по моделям,
  провайдерам и ключам.
- **5-часовые окна** (как лимиты подписки Claude): у каждого клиентского
  ключа свой цикл — окно стартует с первого запроса после простоя и живёт
  5 часов; дашборд показывает текущее окно, историю окон, итоги за неделю и
  месяц и средний расход на 5 часов.
- **Веб-дашборд** (React + TS): карточки периодов, stacked-график таймлайна,
  таблицы по моделям/провайдерам, история окон, автообновление.
- **Латентность запросов**: ttft (время до первого токена провайдера),
  длительность ответа провайдера и полное время ответа клиенту — средние
  и p95 по бакетам час/день на дашборде; по разнице метрик виден оверхед
  прокси и кэша.
- **Журнал событий сервера**: страница «События» — ошибки ключей и
  провайдеров, кулдауны и фолбэки, старт/стоп сервера; события уровня
  WARN/ERROR захватываются из логов приложения автоматически (Logback-
  аппендер, порог — настройка), хранятся в БД с обрезкой stack trace,
  асинхронная запись без влияния на запросы; фильтры по уровню/источнику/
  подстроке, курсорная подгрузка, очистка из UI, автоочистка старше
  `retention-days`.
- **Basic Auth для production**: дашборд и статистика закрываются парой
  логин/пароль из конфига (лёгкий WebFilter, без Spring Security); API
  прокси (`/v1/*`) всегда работает на собственных ключах.

## Архитектура

```text
Claude Code / SDK ──Anthropic API──▶ claudeproxy (Kotlin, Spring WebFlux)
                                        │  auth ▶ модель-роутинг ▶ перевод протокола
                                        │  учёт токенов ▶ SQLite / PostgreSQL
                                        ├─▶ Anthropic-совместимые (pass-through)
                                        ├─▶ OpenAI-совместимые (перевод Claude↔OpenAI)
                                        └─▶ /api + дашборд (React)
```

Стек: **Kotlin 2.3 + Spring Boot 4.1 WebFlux** — реактивный, на корутинах и
Flow (SSE-стриминг без блокировки потоков), **SQLite** (JDBC на выделенном
диспетчере), **kotlin-logging** поверх SLF4J/Logback, **React + TypeScript +
Vite + Recharts**.

Подробности — в [PLAN.md](PLAN.md).

## Быстрый старт

Требования: JDK 21, Node.js 20+ (для дашборда).

1. **Конфигурация** — скопируйте пример и заполните ключи через переменные
   окружения:

   ```bash
   cp config/application.example.yml config/application.yml
   export ANTHROPIC_API_KEY=... OPENROUTER_API_KEY=... PROXY_KEY_LAPTOP=cpk_...
   ```

   Схема конфига: провайдеры (`type: anthropic | openai`, `base-url`,
   `api-key`, маппинг моделей), клиентские ключи прокси, параметры окна.
   Полное описание — в [PLAN.md](PLAN.md#4-конфигурация).

2. **Запуск бэкенда** (порт 8080, слушает 127.0.0.1):

   ```bash
   scripts\run-local.bat        # Windows
   ./scripts/run-local.sh       # Linux
   ```

   (это `bootRun` + пересборка дашборда при наличии Node.js; можно и просто `./gradlew bootRun`)

3. **Дашборд**:

   ```bash
   cd web && npm install && npm run dev   # dev-режим с прокси на :8080
   ```

   Либо `npm run build` — собранный дашборд раздаётся самим бэкендом на `/`.

4. **Production-сборка одной командой** (дашборд встраивается в jar):

   ```bash
   scripts\build-production.bat        # Windows
   ./scripts/build-production.sh       # Linux
   java -jar build/libs/claudeproxy-0.0.1-SNAPSHOT.jar
   ```

5. **Docker** (образ с jar + docker-compose, SQLite или PostgreSQL —
   разделы [Docker](#docker) и [PostgreSQL](#postgresql) ниже):

   ```bash
   scripts\docker-build.bat && docker compose up -d
   ```

## Production

При выносе прокси в сеть включите **Basic Auth дашборда** (config/application.yml) —

```yaml
claudeproxy:
  dashboard:
    auth:
      username: admin
      password: ${DASHBOARD_PASSWORD}
```

– и осознанно меняйте адрес прослушивания (`server.address`). Эндпоинты `/v1/*`
под Basic Auth не ставятся: клиенты аутентифицируются api-ключами прокси.
Пока учётные данные не заданы, прокси должен слушать только `127.0.0.1`.

**JSON-логи.** Для серверного окружения и систем сбора логов включите
Spring-профиль `json` — логи пишутся построчно в Logstash JSON
(`SPRING_PROFILES_ACTIVE=json` или `--spring.profiles.active=json`).
Без профиля вывод остаётся человекочитаемым.

**Бэкапы SQLite.** Включите периодический снапшот базы (`VACUUM INTO`,
без остановки записи) — расписание по cron, хранение N последних копий:

```yaml
claudeproxy:
  backup:
    enabled: true
    directory: backups      # каталог относительно рабочей папки
    retention-count: 7
    # cron: "0 53 3 * * *"  # по умолчанию ежедневно в 03:53
```

Для PostgreSQL задача бэкапа неактивна — используйте штатные средства
(`pg_dump`, репликация).

## Подключение Claude Code

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080
export ANTHROPIC_API_KEY=cpk_...        # ключ прокси, не провайдера
claude
```

Важно: в `ANTHROPIC_BASE_URL` — **корень прокси без `/v1`** (Claude Code сам
добавляет `/v1/messages`; с `…:8080/v1` получится `/v1/v1/messages` и 404).
Модели выбираются как обычно (`/model …`) — прокси маршрутизирует по
провайдерам из вашего конфига. Быстрая проверка:

```bash
curl http://127.0.0.1:8080/v1/models -H "x-api-key: cpk_..."
```

## Подключение OpenAI-клиентов

Прокси принимает и протокол OpenAI: любой клиент с OpenAI SDK работает со всеми
моделями прокси через `POST /v1/chat/completions` (стриминг, tool calls;
провайдеры обоих типов — openai и anthropic — доступны одинаково).

```bash
export OPENAI_BASE_URL=http://127.0.0.1:8080/v1
export OPENAI_API_KEY=cpk_...           # тот же ключ прокси
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="cpk_...")
response = client.chat.completions.create(model="gpt-5.2", messages=[...])
```

`GET /v1/models` возвращает надмножество полей Anthropic и OpenAI — один
эндпоинт корректно читают оба SDK.

## Файлы и каталоги

| Путь | Назначение |
| --- | --- |
| `config/application.yml` | рабочий конфиг (в git не попадает) |
| `config/application.example.yml` | пример конфигурации |
| `data/claudeproxy.db` | SQLite со статистикой (в git не попадает) |
| `web/` | исходники дашборда |
| `Dockerfile`, `docker-compose*.yml` | образ и запуск в Docker (SQLite / PostgreSQL) |
| `src/main/resources/db/migration/{sqlite,postgres}/` | миграции по диалектам БД |

## Docker

Образ — JRE 21 + готовый boot-jar (дашборд уже внутри). Сборка:

```bat
scripts\docker-build.bat          # тесты → bootJar → docker build claudeproxy:local
```

Запуск со встроенным SQLite (база и конфиг — в томах хоста):

```bash
docker compose up -d              # порты: 8080:8080, тома: ./data, ./config
```

## PostgreSQL

Вместо встроенного SQLite — внешний PostgreSQL: свои миграции того же
нумерованного набора (db/migration/postgres/), диалект выбирается
автоматически по JDBC-URL. Данные из SQLite не переносятся (статистика
и ключи заводятся заново).

В Docker — второй compose-файл поверх базового (пароль в `.env` рядом):

```bash
echo POSTGRES_PASSWORD=... > .env
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up -d
```

Вне Docker — секция `spring.datasource` в config/application.yml
(см. закомментированный блок в application.example.yml; не забудьте
`driver-class-name: org.postgresql.Driver` и `maximum-pool-size` > 1).

Интеграционный тест диалекта — `PostgresMigrationIntegrationTest`; он
пропускается без переменных окружения и запускается против одноразовой
пустой базы:

```bash
docker run -d --name pg-claudeproxy-test -p 55432:5432 \
  -e POSTGRES_DB=claudeproxy -e POSTGRES_USER=claudeproxy -e POSTGRES_PASSWORD=claudeproxy \
  postgres:17-alpine
CLAUDEPROXY_POSTGRES_TEST_URL=jdbc:postgresql://localhost:55432/claudeproxy \
CLAUDEPROXY_POSTGRES_TEST_USERNAME=claudeproxy \
CLAUDEPROXY_POSTGRES_TEST_PASSWORD=claudeproxy \
  ./gradlew test --tests '*PostgresMigrationIntegrationTest'
```

## Разработка

```bash
./gradlew test          # тесты бэкенда
cd web && npm run build # сборка дашборда в web/dist
```

План работ и статус этапов — в [PLAN.md](PLAN.md#13-этапы).

## Статус

✅ **v1 реализована** (M1–M5 из [PLAN.md](PLAN.md#13-этапы)): проксирование с полным
переводом протокола, учёт токенов с 5-часовыми окнами, дашборд, управление ключами,
Basic Auth для production, retention-очистка статистики, встраивание дашборда в jar.
