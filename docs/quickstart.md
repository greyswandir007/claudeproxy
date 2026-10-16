# Быстрый старт

Цель этой страницы — провести вас от свежего клона до первого запроса
через claudeproxy за 10–15 минут. Подробнее о повседневной работе —
в [usage.md](usage.md), о всех параметрах — в [settings.md](settings.md).

## Что понадобится

- **JDK 21+** — для сборки (запускать собранный jar можно на JRE 21).
- **Node.js 18+** — для сборки дашборда; если его нет, jar соберётся без
  дашборда и прокси продолжит работать (смотри консоль при сборке).
- Ключ хотя бы одного провайдера: Anthropic или любого
  OpenAI-совместимого (OpenRouter, DeepSeek и др.).
- Docker — опционально, альтернативный способ запуска.

## Шаг 1. Конфигурация

```bash
cp config/application.example.yml config/application.yml
```

Откройте `config/application.yml` и:

1. Впишите ключ провайдера в переменную окружения (не в сам файл —
   так ключ не попадёт в backups и в shell history):

   ```bash
   export ANTHROPIC_API_KEY=sk-ant-...
   ```

2. Определитесь с клиентскими ключами. Проще всего начать с одного
   сида — раскомментируйте `api-keys` и задайте значение для ключа:

   ```bash
   export PROXY_KEY_LAPTOP=cpk_<любая случайная строка>
   ```

   Позже ключи удобнее генерировать на странице «Ключи» дашборда.

3. Проверьте секцию `providers`: имя, тип (`anthropic` или `openai`),
   `base-url`, модели (пара `public`/`upstream`). Пример в файле уже
   рабочий — для старта достаточно заменить ключ.

`config/application.yml` не попадает в git (`.gitignore`).

## Шаг 2. Сборка и запуск

Вариант A — обычный jar:

```bash
./scripts/build-production.sh
java -jar app/build/libs/claudeproxy-*.jar
```

Вариант B — локальная сборка с портом 9090 и базой рядом с местом
запуска (удобно держать вне репозитория):

```bash
./scripts/build-local.sh
cd build/local && java -jar claudeproxy.jar
```

Вариант C — Docker:

```bash
docker compose up -d
```

При старте прокси создаёт SQLite-базу `data/claudeproxy.db` (в
каталоге запуска) и применяет миграции автоматически.

## Шаг 3. Ключ клиента

Откройте дашборд: **http://localhost:8080** (порт из `server.port`;
локальная сборка из `scripts/build-local.sh` слушает 9090).

Если при сборке дашборд не собрался (нет Node.js) — сгенерируйте ключ
вручную, например `openssl rand -hex 16` с префиксом `cpk_`, и
перезапустите прокси с этим сидом из шага 1.

## Шаг 4. Первый запрос

Smoke-тест curl-ом (подставьте свой ключ):

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: cpk_..." -H "content-type: application/json" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"claude-sonnet-5","max_tokens":64,"messages":[{"role":"user","content":"Скажи привет"}]}'
```

Подключение Claude Code:

```bash
export ANTHROPIC_BASE_URL=http://localhost:8080
export ANTHROPIC_AUTH_TOKEN=cpk_...
claude
```

OpenAI-совместимые клиенты подключаются к тому же порту:

```python
client = OpenAI(base_url="http://localhost:8080/v1", api_key="cpk_...")
```

Если в ответ пришёл текст модели — всё работает. Дальше:
[usage.md](usage.md) — ежедневные сценарии, [settings.md](settings.md) —
все параметры, [api.md](api.md) — эндпоинты.
