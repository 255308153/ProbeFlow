#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== ProbeFlow V4 deterministic verification =="
echo "This script does not call a real LLM, real embedding, or external business HTTP."

mvn -Dtest=DemoRunApplicationServiceIssue01Tests,DemoRunApiIssue02Tests,DemoConsoleIssue03Tests,DemoRunApplicationServiceIssue04Tests,DemoRunApplicationServiceIssue05Tests,DemoRunApplicationServiceIssue06Tests test

cat <<'INFO'

Manual API examples after starting the backend with:
  docker compose up -d
  mvn spring-boot:run

Fake baseline:
  curl -s http://localhost:8080/api/v4/demo-runs \
    -H 'Content-Type: application/json' \
    -d '{"fixtureId":"order-suite-demo","providerMode":"fake","runProfile":"local-demo","comparison":false,"allowMemoryWrite":false}'

Real LLM config validation:
  curl -s http://localhost:8080/api/v4/demo-runs \
    -H 'Content-Type: application/json' \
    -d '{"fixtureId":"order-suite-demo","providerMode":"real","runProfile":"manual-real-llm","allowMemoryWrite":false}'

Comparison:
  curl -s http://localhost:8080/api/v4/demo-runs \
    -H 'Content-Type: application/json' \
    -d '{"fixtureId":"order-suite-demo","providerMode":"comparison","runProfile":"comparison-demo","comparison":true,"allowMemoryWrite":false}'

Demo Console:
  http://localhost:8080/v4/demo-console
INFO
