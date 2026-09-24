#!/bin/sh
# Local claudeproxy launch: build the dashboard (if Node.js is available) and run bootRun.
set -e
cd "$(dirname "$0")/.."

if command -v npm >/dev/null 2>&1; then
    echo "[run-local] Building dashboard..."
    ./gradlew buildDashboard --console=plain
else
    echo "[run-local] npm not found - dashboard is not rebuilt (if web/dist exists, it will be served as is)."
fi

echo "[run-local] Starting backend: http://127.0.0.1:8080"
exec ./gradlew bootRun --console=plain
