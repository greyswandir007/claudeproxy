#!/bin/sh
# Production claudeproxy build: the dashboard is embedded into the jar.
set -e
cd "$(dirname "$0")/.."

./gradlew clean buildDashboard bootJar --console=plain

echo ""
echo "[build-production] Done: build/libs/claudeproxy-0.0.1-SNAPSHOT.jar"
echo "[build-production] Run:    java -jar build/libs/claudeproxy-0.0.1-SNAPSHOT.jar"
