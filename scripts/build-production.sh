#!/bin/sh
# Production-сборка claudeproxy: дашборд встраивается в jar.
set -e
cd "$(dirname "$0")/.."

./gradlew clean buildDashboard bootJar --console=plain

echo ""
echo "[build-production] Готово: build/libs/claudeproxy-0.0.1-SNAPSHOT.jar"
echo "[build-production] Запуск:   java -jar build/libs/claudeproxy-0.0.1-SNAPSHOT.jar"
