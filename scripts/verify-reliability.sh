#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
resilience_test_container="resilience-reliability-$$"
cleanup() { docker rm -f "$resilience_test_container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run --rm -d --name "$resilience_test_container" --memory=128m --cpus=0.5 \
  -p 127.0.0.1::6379 redis:7.4.6-alpine redis-server \
  --maxmemory 64mb --maxmemory-policy noeviction --save '' --appendonly no >/dev/null
resilience_test_port=$(docker port "$resilience_test_container" 6379/tcp | sed 's/.*://')
export REDIS_TEST_URI="redis://127.0.0.1:$resilience_test_port"
mvn -B -Pexternal,soak "-Dsoak.seconds=${SOAK_SECONDS:-60}" verify "$@"
