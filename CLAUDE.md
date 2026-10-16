# claudeproxy — соглашения по коду

## Структура модулей (multi-module Gradle)

Зависимости — строго вниз по списку, циклов нет:

- `model` — общие контракты без логики: `ProxyProperties`, `UsageEvent`,
  `ServerEvent`, типы ошибок протокола (`ApiError`, `UpstreamError` и др.).
- `database` — доступ к БД (`db`, `db/impl`), миграции
  (`database/src/main/resources/db/migration`). Зависит от `model`.
- `service` — бизнес-сервисы (`auth`, `chat`, `providers`, `routing`,
  `serverevent`, `usage`, `proxy/cache`) с реализациями в подпакетах `impl`.
  Зависит от `model`, `database`.
- `proxy` — ядро проксирования (`proxy`, `proxy/openai`). Зависит от `model`,
  `service`.
- `api` — REST дашборда (`api`). Зависит от `model`, `service`, `proxy`.
- `app` — точка входа (`ClaudeproxyApplication`), вся конфигурация
  (`application.yml`, logback, `config`, `http`), пайплайн дашборда
  (задачи `buildDashboard`/`copyDashboardIntoJar`) и **все тесты**.
  Зависит от всех модулей; bootJar — `app/build/libs/claudeproxy-…jar`.

Имена пакетов (`ru.wizard.web.claudeproxy.*`) не привязаны к модулям: один
пакет может существовать в нескольких модулях, компонент-скан приложения
находит бины по всему classpath. Новые общие типы кладутся в `model`.

## Соглашения по коду

- Один файл — один верхнеуровневый класс/интерфейс/объект (вложенные объявления
  и companion object — можно).
- Сервисные бины: интерфейс в пакете, реализация — в подпакете `impl` того же
  пакета (например, `ApiKeyService` + `auth/impl/JdbcApiKeyService`).
  Адаптеры обвязки — контроллеры, WebFilter-ы, `@Configuration`,
  `@ConfigurationProperties` — конкретные классы без собственных интерфейсов.
- Без сокращений в именах классов, функций, переменных и констант. Исключение —
  общепринятые названия протоколов и форматов: SSE, HTTP, JSON, API, SHA-256.
- Доступ к БД — только через интерфейс `DatabaseProvider`
  (реализация — `SqliteDatabaseProvider`); прямое обращение к диспетчеру БД
  из сервисов не допускается.
- Логирование — kotlin-logging (`KotlinLogging.logger {}`) поверх SLF4J;
  ключи и тела запросов не логируются.
- Комментарии — на русском языке; **log-сообщения — на английском (только ASCII)**,
  чтобы не зависеть от кодировки консоли Windows. Скрипты `scripts/` (bat/sh) —
  полностью на английском по той же причине.
- Стиль — реактивный WebFlux + корутины: suspend-функции, без блокировки
  event-loop; стриминг — Flux/Flow без агрегации.
