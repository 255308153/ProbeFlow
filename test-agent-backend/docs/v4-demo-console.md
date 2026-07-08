# ProbeFlow V4 Demo Console

V4 turns the existing Manual Suite Agent Harness into a local, runnable demo product surface:

```text
Demo Console
-> Demo Run API
-> DemoRunApplicationService
-> Manual Suite Agent Harness
-> controlled planner, tool contract, policy checks, unified context
-> suite draft, execution, variable audit, failure analysis, memory feedback, evaluation
-> JSON / Markdown artifacts
```

The highest application seam is `DemoRunApplicationService`. V4 does not create a second agent orchestration path; it wraps the existing harness with a stable demo result contract for the API and console.

## Prerequisites

- Java 21
- Maven 3.9+
- Optional for running the Spring Boot app outside tests: local PostgreSQL and Redis via `docker compose up -d`

Automated tests use the `test` profile with H2. They do not need PostgreSQL, Redis, a real LLM key, real embedding, or external business HTTP.

## Fake Demo

The fake baseline is the default and safest demo path. It uses deterministic provider output and the fake HTTP gateway.

```bash
cd test-agent-backend
mvn -Dtest=DemoRunApplicationServiceIssue01Tests,DemoRunApiIssue02Tests,DemoConsoleIssue03Tests test
```

To exercise the Demo Run API through Spring Boot:

```bash
cd test-agent-backend
docker compose up -d
mvn spring-boot:run
```

Then run:

```bash
curl -s http://localhost:8080/api/v4/demo-runs \
  -H 'Content-Type: application/json' \
  -d '{
    "fixtureId": "order-suite-demo",
    "providerMode": "fake",
    "runProfile": "local-demo",
    "comparison": false,
    "allowMemoryWrite": false,
    "outputFormats": ["JSON_REPORT", "MARKDOWN_REPORT"],
    "outputDirectory": "target/v4-demo-run"
  }'
```

Expected fake result:

- `status`: `COMPLETED`
- `providerMode`: `FAKE`
- `usesRealLlm`: `false`
- `usesExternalHttp`: `false`
- `provider.fakeBaseline`: `true`
- Core sections present: `plan`, `context`, `tools`, `suite`, `execution`, `variableAudit`, `failureAnalysis`, `memoryFeedback`, `evaluation`

## Demo Console

Start the backend:

```bash
cd test-agent-backend
docker compose up -d
mvn spring-boot:run
```

Open:

```text
http://localhost:8080/v4/demo-console
```

The first screen is the runnable Agent demo. Select a fixture, choose `Fake baseline`, `Real LLM manual`, or `Comparison`, then run. The console calls `POST /api/v4/demo-runs` and renders the same stable result contract used by JSON and Markdown artifacts.

## Real LLM Manual Mode

Real LLM mode is only for explicit local experiments. It still goes through the existing `LlmProvider` seam and the LLM policy, audit, sanitizer, timeout, token usage, and error classification path.

Required runtime request values:

- `providerMode`: `real`
- `runProfile`: `manual-real-llm`
- `allowMemoryWrite`: normally `false`; only set to `true` for an explicit memory-write experiment

Required environment configuration:

```bash
export PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS=true
export PROBEFLOW_LLM_ALLOWED_PROVIDERS=fake,manual-real
export PROBEFLOW_LLM_MANUAL_REAL_ENABLED=true
export PROBEFLOW_LLM_MANUAL_REAL_PROVIDER=manual-real
export PROBEFLOW_LLM_MANUAL_REAL_ENDPOINT=https://example.local/llm
export PROBEFLOW_LLM_MANUAL_REAL_KEY=replace-with-local-secret
export PROBEFLOW_LLM_MANUAL_REAL_MODEL=replace-with-model
export PROBEFLOW_LLM_MANUAL_REAL_TIMEOUT_MS=30000
export PROBEFLOW_LLM_MANUAL_REAL_MAX_TOKENS=1024
export PROBEFLOW_LLM_MANUAL_REAL_COST_LIMIT_CENTS=50
```

If `runProfile` is not `manual-real-llm`, V4 returns `REJECTED` with a clear configuration diagnostic. If endpoint, key, model, timeout, max tokens, or cost limit are missing, V4 returns a rejected demo result with `REAL_LLM_NOT_AVAILABLE`. If policy blocks the configured provider, V4 returns `REAL_LLM_POLICY_BLOCKED`. These failures are part of the demo result; default CI and fake demo runs still pass without real LLM config.

## Comparison Mode

Comparison mode runs the deterministic fake baseline and attempts a real LLM run through the same provider seam. It is designed to show differences, not to claim the real LLM is automatically better.

```bash
curl -s http://localhost:8080/api/v4/demo-runs \
  -H 'Content-Type: application/json' \
  -d '{
    "fixtureId": "order-suite-demo",
    "providerMode": "comparison",
    "runProfile": "comparison-demo",
    "comparison": true,
    "allowMemoryWrite": false,
    "outputFormats": ["JSON_REPORT", "MARKDOWN_REPORT"],
    "outputDirectory": "target/v4-demo-run"
  }'
```

When real LLM config is absent, comparison still completes with a fake baseline and a rejected real-run section. This is useful evidence that the fake path remains stable and real mode is isolated.

Read these comparison fields:

- `comparison.fakeBaseline`: fake run status, provider mode, run id, and dependency flags
- `comparison.realRun`: real run status, provider mode, LLM diagnostics, and dependency flags
- `comparison.planStepDifferences`
- `comparison.toolSelectionDifferences`
- `comparison.failureAnalysisDifferences`
- `comparison.memoryFeedbackDifferences`
- `comparison.reportSummaryDifferences`
- `comparison.memoryWriteSuppressed`: always `true` for comparison

## Artifacts

Default output directory:

```text
target/v4-demo-run
```

Single fake or real runs produce:

- `JSON_REPORT`
- `MARKDOWN_REPORT`

Comparison runs also produce:

- `COMPARISON_JSON_REPORT`
- `COMPARISON_MARKDOWN_REPORT`

Key result fields:

- `schemaVersion`: currently `v4-demo-run-result.v1`
- `runId`: unique id for the demo run
- `fixtureId` and `fixtureVersion`: fixture identity
- `providerMode`: `FAKE`, `REAL`, or `COMPARISON`
- `status`: `COMPLETED`, `FAILED`, or `REJECTED`
- `runProfile`: `local-demo`, `manual-real-llm`, or `comparison-demo`
- `usesRealLlm`: whether a real provider actually succeeded
- `usesExternalHttp`: visible proof that the default demo does not hit real business HTTP
- `provider`: provider summary, output formats, policy notes, and LLM call summaries
- `plan`, `context`, `tools`, `suite`, `execution`, `variableAudit`, `failureAnalysis`, `memoryFeedback`, `evaluation`, `comparison`, `errors`: displayable Agent sections
- `artifacts`: JSON and Markdown artifact references
- `diagnostics`: configuration, fixture, provider, policy, or system diagnostics

The Markdown artifact is for interviews and documentation. The JSON artifact is for automated checks, replay, or later evaluation tooling.

## What The Demo Shows

Use this narration in a manual walkthrough:

1. The planner is controlled by the Manual Suite Agent Harness, not a free-form loop.
2. Tool calls are represented by known application services behind a Tool Contract.
3. Policy and provider decisions stay visible in summaries and diagnostics.
4. Unified Context is shown as task memory, API context, runtime context, knowledge context, and long-term memory context.
5. ExecutionContext drives suite execution, variable extraction, write-back, and variable audit.
6. Failure Analysis classifies root cause, downstream impact, and next suggestion.
7. Memory Feedback shows the learnable candidate and write-safety decision.
8. Evaluation Comparison proves the Agent behavior has a deterministic scoring loop.
9. Fake baseline keeps the demo stable; real LLM mode is isolated for manual experiments.

## V4 Boundary

V4 is not RAG Pro or Memory Pro.

V4 does not implement:

- real embedding ingestion
- pgvector production retrieval
- BM25 or PostgreSQL full-text retrieval
- Query Rewrite
- multi-recall
- RRF
- Cross-Encoder rerank
- LLM rerank
- Small-to-Big indexing
- production Memory Engine governance
- production Context Engine strengthening
- login, tenant isolation, RBAC, or a full management backend

Those belong to V5: real embedding, pgvector, multi-recall, rerank, Small-to-Big, and stronger self-built Memory Engine and Context Engine.

V4 also does not directly depend on mem0 or VikingDB. The project may borrow design ideas later, but later memory and retrieval capabilities should be self-built inside ProbeFlow rather than hidden behind those SDKs.

## Manual Verification

Run the deterministic V4 verification script:

```bash
cd test-agent-backend
./scripts/verify-v4-demo.sh
```

The script covers:

- fake baseline contract
- Demo Run API schema
- Demo Console static page
- real LLM missing-config and policy failure behavior
- comparison mode with memory write suppressed
- redaction and memory-write safety boundaries

For a full local gate, also run:

```bash
cd test-agent-backend
mvn test
```
