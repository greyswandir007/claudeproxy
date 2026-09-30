#!/bin/sh
# Local claudeproxy build: assemble build/local/claudeproxy.jar with local
# defaults baked in (server port 9090, sqlite path relative to the launch dir).
# Run the jar from any directory - the database is created next to it, so
# keeping it outside the repository keeps the project clean.
set -e
cd "$(dirname "$0")/.."

if command -v npm >/dev/null 2>&1; then
    echo "[build-local] Building dashboard..."
    ./gradlew buildDashboard --console=plain
else
    echo "[build-local] npm not found - dashboard is not rebuilt (if web/dist exists, it will be served as is)."
fi

./gradlew bootJar --console=plain

RUN_DIR="$(pwd)/build/local"
STAGING="$RUN_DIR/jar-staging"
mkdir -p "$RUN_DIR"
rm -rf "$STAGING"
mkdir -p "$STAGING/BOOT-INF/classes"

# Baked-in local defaults: source application.yml + local document -> jar entry.
cat app/src/main/resources/application.yml scripts/local-defaults.yml \
    > "$STAGING/BOOT-INF/classes/application.yml"

cp app/build/libs/claudeproxy-0.0.1-SNAPSHOT.jar "$RUN_DIR/claudeproxy.jar"
cd "$STAGING"
jar uf "$RUN_DIR/claudeproxy.jar" BOOT-INF/classes/application.yml
cd - >/dev/null
rm -rf "$STAGING"

echo ""
echo "[build-local] Done: build/local/claudeproxy.jar - server port 9090 is baked in"
echo "[build-local] Run from any directory: java -jar claudeproxy.jar"
