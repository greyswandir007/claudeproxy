#!/usr/bin/env sh
# Build boot jar (with dashboard when Node.js is available) and docker image claudeproxy:local.
set -e
cd "$(dirname "$0")/.."

./gradlew test
./gradlew bootJar

if [ ! -f "app/build/libs/claudeproxy-0.0.1-SNAPSHOT.jar" ]; then
    echo "Docker build failed: jar not found" 1>&2
    exit 1
fi

docker build -t claudeproxy:local .

echo "Done: docker image claudeproxy:local"
