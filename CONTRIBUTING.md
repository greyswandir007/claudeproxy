# Contributing

Thanks for considering a contribution!

## Getting started

```bash
git clone <your-fork>
cd claudeproxy
./gradlew test        # JDK 21+; runs unit + integration tests (no Node needed)
```

The dashboard (React + TypeScript in `web/`) needs Node.js 20.19+:

```bash
cd web && npm install && npm run dev   # Vite dev server proxies /api
```

## Before opening a PR

1. `./gradlew build` is green (this also runs the dashboard build when
   Node.js is available).
2. New behavior comes with tests — see `app/src/test/kotlin` for the
   existing patterns (integration tests use in-process reactor-netty fakes
   and isolated SQLite databases under `build/test`).
3. Follow the code conventions in [CLAUDE.md](CLAUDE.md): reactive
   WebFlux + coroutines, one top-level declaration per file, an interface
   in the package with the implementation in its `impl` subpackage,
   Russian code comments, ASCII-only log messages.
4. Keep the dashboard UI text in Russian for now.

## Branching model

- `main` is the development line. Direct pushes are blocked: changes land
  through pull requests from `feature/<name>` (new functionality) or
  `bugfix/<name>` (fixes). The source-branch policy is enforced by the
  `Source branch policy` CI check.
- `release/<major.minor>` are long-lived stable streams cut from `main` by
  the maintainer; `vX.Y.0` is tagged on the stream. Only `bugfix/<name>`
  pull requests may target a release stream — fix `main` first when it is
  affected, then cherry-pick the fix to the stream.
- Patch versions `vX.Y.Z` are tagged on the corresponding release stream.
- CI checks must pass before merging, and an approving review is required.
- Squash is the only merge method: one commit per pull request keeps the
  history linear and readable.

## Reporting issues

Include the claudeproxy version, a minimal reproduction, and relevant
fragments of the server event journal (dashboard → «События»). Never paste
real API keys — client keys are safe to mention by name only.
