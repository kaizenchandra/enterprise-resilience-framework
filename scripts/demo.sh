#!/bin/sh
set -eu
: "${DEMO_ADMIN_TOKEN:?Set DEMO_ADMIN_TOKEN}"
curl --fail http://localhost:18080/demo
curl --fail -X POST http://localhost:18082/fault -H "X-Admin-Token: $DEMO_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"failurePercent":100,"delayMillis":0,"status":503,"partial":false}'
for i in $(seq 1 20); do curl -s http://localhost:18080/demo; done
curl --fail http://localhost:18081/actuator/resilience
curl --fail -X POST http://localhost:18082/fault -H "X-Admin-Token: $DEMO_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"failurePercent":0,"delayMillis":0,"status":503,"partial":false}'
sleep 6
for i in $(seq 1 6); do curl --fail http://localhost:18080/demo; done
