#!/bin/sh
# Локальный запуск claudeproxy: сборка дашборда (если есть Node.js) и bootRun.
set -e
cd "$(dirname "$0")/.."

if command -v npm >/dev/null 2>&1; then
    echo "[run-local] Сборка дашборда..."
    ./gradlew buildDashboard --console=plain
else
    echo "[run-local] npm не найден — дашборд не пересобирается (если web/dist существует, он будет роздан)."
fi

echo "[run-local] Запуск бэкенда: http://127.0.0.1:8080"
exec ./gradlew bootRun --console=plain
