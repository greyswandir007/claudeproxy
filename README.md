# claudeproxy

A local gateway (Kotlin, Spring WebFlux) between Claude clients and LLM
providers: it stands in for `api.anthropic.com` for Anthropic-compatible
providers, translates the protocol for OpenAI-compatible ones, tracks token
usage with Claude-subscription-style 5-hour windows, retries and fails over
between providers, and ships a built-in dashboard.

> Documentation in [`docs/`](docs/) is written in Russian.

- [docs/quickstart.md](docs/quickstart.md) — from a fresh clone to the first request
- [docs/usage.md](docs/usage.md) — everyday usage guide
- [docs/architecture.md](docs/architecture.md) — how it works inside
- [docs/settings.md](docs/settings.md) — configuration reference
- [docs/api.md](docs/api.md) — HTTP endpoints

## Features

- **Transparent Claude API proxying**: `/v1/messages` (SSE and JSON, tool
  calls, thinking, beta headers), `/v1/messages/count_tokens`,
  `/v1/models`, the Batches API, files.
- **Claude ↔ OpenAI protocol translation**: OpenAI providers serve the same
  public models, including streaming and tool calls; inbound OpenAI
  compatibility — `POST /v1/chat/completions` — works for every model.
- **Routing and resilience**: priorities + round-robin, retries and
  failover to the next provider, cooldowns, sticky conversation affinity
  (preserves upstream prompt cache).
- **Client keys and windows**: proxy keys with SHA-256 hashes, quotas
  (window / week / 30 days), 5-hour windows like a Claude subscription.
- **Usage analytics**: usage by model/provider/key, timeline, latency,
  costs (per_million / monthly), error details.
- **Request cache**: idempotent requests deduplicated by canonical-body
  SHA-256; streamed answers are replayed from cache (TTL 5 min / 1 h).
- **Token optimizer**: an optional model-driven compressor for old
  `tool_result` history, with caching, a breaker and savings statistics.
- **Provider proxies**: outbound calls of a provider can go through an
  HTTP(S) CONNECT or SOCKS4/5 endpoint, with a connectivity check in the UI.
- **count_tokens calibration** for OpenAI providers, learned from actual
  responses.
- **Dashboard** (React): models/providers, keys, windows, server events, a
  built-in chat playground; optional Basic Auth.
- **Storage**: SQLite (single file, scheduled snapshot backups) or
  PostgreSQL.

## Quick start

Requires JDK 21+. Build and run in dev mode:

```bash
./gradlew :app:bootRun          # http://localhost:8080, SQLite data/claudeproxy.db
```

Production build (adds the dashboard; needs Node.js 20.19+ for the web
build):

```bash
./gradlew bootJar               # app/build/libs/claudeproxy-0.1.0.jar
java -jar app/build/libs/claudeproxy-0.1.0.jar
```

Initial setup happens in the dashboard: create a provider (type, base URL,
API key), add a model with a public name, create a client key (the secret
is shown once). An annotated configuration example lives in
[`config/application.example.yml`](config/application.example.yml).

### Connecting clients

```bash
# Claude Code
export ANTHROPIC_BASE_URL=http://localhost:8080
export ANTHROPIC_AUTH_TOKEN=cpk_...        # or ANTHROPIC_API_KEY

# OpenAI SDK (inbound compatibility)
client.base_url = "http://localhost:8080/v1"
client.api_key  = "cpk_..."
```

A curl smoke test:

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: cpk_..." -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-4-5","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}'
```

## Production notes

- Expose the dashboard beyond localhost only together with its Basic Auth
  (`claudeproxy.dashboard.auth`, see [docs/settings.md](docs/settings.md)).
- Client keys are stored as SHA-256 hashes and never logged; provider
  secrets can be kept as `${ENV_VAR}` references.
- Logs are human-readable by default; `SPRING_PROFILES_ACTIVE=json`
  switches to line-delimited JSON. WARN/ERROR also land in the dashboard
  event journal.
- SQLite backups — `claudeproxy.backup.*` (off by default); for PostgreSQL
  use `pg_dump`.
- Migrations apply on startup; per-dialect sets live in
  `database/src/main/resources/db/migration/`.

## Development

`./gradlew build` compiles, runs all tests (unit + integration with
in-process reactor-netty fakes; test databases are isolated under
`build/test`) and builds the dashboard when Node.js is available. Dashboard
frontend: `cd web && npm install && npm run dev` (Vite dev server proxies
`/api` to the backend). Code conventions — see [CLAUDE.md](CLAUDE.md);
contributions are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
