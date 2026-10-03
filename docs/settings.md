# Настройки claudeproxy

Источник правды — `model/src/main/kotlin/.../config/ProxyProperties.kt`;
рабочий пример с комментариями — `config/application.example.yml`.
Префикс всех настроек — `claudeproxy.*`.

## Сводная таблица

| Группа | Ключи | Умолчание | Назначение |
| --- | --- | --- | --- |
| `window-hours` | — | `5` | длина скользящего окна ключа, часы |
| `retention-days` | — | `365` | автоочистка `usage_event`, дней (0 — не чистить) |
| `api-keys` | `name`, `key` | — | сид клиентских ключей при старте |
| `models`, `providers` | см. пример | — | сид моделей и провайдеров при старте |
| `dashboard.auth` | `username`, `password` | — | Basic Auth дашборда (пусто — выключен) |
| `request-cache` | `max-rows` | `1000` | лимит строк кэша запросов (LRU) |
| `conversation-affinity` | `enabled`, `ttl-seconds`, `max-entries` | `false` / `3600` / `1000` | sticky-аффинность разговоров |
| `token-calibration` | `enabled`, `minimum-samples` | `true` / `20` | самообучение count_tokens для openai |
| `server-event` | `queue-capacity`, `retention-days`, `min-level` | `1000` / `14` / `WARN` | журнал событий сервера |
| `backup` | `enabled`, `directory`, `retention-count`, `cron` | `false` | снапшоты SQLite (`VACUUM INTO`) |
| `optimizer` | `enabled`, `provider`, `model`, `budget` и др. | `false` | модель-оптимизатор токенов (M30) |
| `upstream` | `connect-timeout-milliseconds`, `read-timeout-seconds` | `10000` / `300` | таймауты исходящих соединений (M31-хотфикс) |

## Клиентские ключи (сид)

```yaml
claudeproxy:
  api-keys:
    - name: laptop          # суффикс после «cpk_»
      key: ${PROXY_KEY_LAPTOP}
```

Ключи, созданные из UI, живут в БД (SHA-256); этот список — только сид
при старте. Клиенты передают ключ в `x-api-key` или `Authorization: Bearer`.

## Провайдеры (сид) и настройки в БД

Сид задаёт провайдеров/модели при старте; рабочее управление — из дашборда
(страница «Модели»), состояние хранится в БД и применяется сразу.
Секреты (`api-key`, OAuth client secret, пароль прокси) допускают литерал
или ссылку `${ENV_VAR}` / `${ENV_VAR:default}`.

Основные поля провайдера: `type` (anthropic | openai), `base-url`,
`api-key`, extra-заголовки, sticky-флаги, лимиты (окно/неделя/30 дней —
информационные), кулдауны (5xx/429/401-403/сеть), поведение (приоритет,
weight, ретраи, таймауты, режим стрима, SSL-валидация), кэш-TTL,
оverрайды оптимизатора (SET-1…SET-16), тарификация (`per_million` /
`monthly` для карточек стоимости) и `proxy-name` — привязка прокси (M31).

## Sticky-аффинность

```yaml
claudeproxy:
  conversation-affinity:
    enabled: true
    ttl-seconds: 3600     # привязка живёт, пока с последнего хода прошло меньше
    max-entries: 1000     # лимит привязок в памяти (переполнение выталкивает старые)
```

Диагностика — `GET /api/conversation-affinity-stats`.

## Журнал событий сервера

```yaml
claudeproxy:
  server-event:
    queue-capacity: 1000   # ёмкость async-очереди; переполнение — события отбрасываются
    retention-days: 14     # автоочистка server_event (0 = не чистить)
    min-level: WARN        # минимальный уровень захвата логов: INFO | WARN | ERROR
```

## Бэкапы SQLite

```yaml
claudeproxy:
  backup:
    enabled: true
    directory: backups     # относительно рабочей папки
    retention-count: 7
    # cron: "0 53 3 * * *" # по умолчанию ежедневно в 03:53
```

Для PostgreSQL задача бэкапа неактивна.

## Оптимизатор токенов (M30)

Глобальный конфиг (страница «Дашборд» → карточка оптимизатора,
`GET/PUT /api/optimizer/config`): включённость, провайдер и модель
сжатия, бюджет. Точечные настройки (порог символов, таймауты, кворум
кеширования и т.д. — полный каталог SET-1…SET-16 с описаниями) живут
в оверрайдах провайдера и в UI видны как раскрывающийся список.

## Таймауты исходящих соединений

```yaml
claudeproxy:
  upstream:
    connect-timeout-milliseconds: 10000
    read-timeout-seconds: 300
```

Действуют на оба пути — прямой и через прокси. `read-timeout-seconds` —
максимальная «тишина» апстрима (ни одного байта); для SSE безопасно,
так как события идут часто. Молчащий апстрим обрывается релейной ошибкой
5xx, а не вечным зависанием.

## Spring-настройки, используемые прокси

| Ключ | Назначение |
| --- | --- |
| `server.port`, `server.address` | порт/адрес прослушивания (до Basic Auth — только `127.0.0.1`) |
| `spring.datasource.url` | `jdbc:sqlite:…` или `jdbc:postgresql:…`; для SQLite работает схема `sqlite-file:…` |
| `spring.datasource.hikari.maximum-pool-size` | для PostgreSQL > 1 |
| `spring.profiles.active=json` | построчные JSON-логи (Logstash) |
