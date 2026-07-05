Status: ready-for-agent

# Issue 01: Failure analysis entrypoint for single ExecutionRecord

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Build the first Phase 7 vertical slice: a caller can analyze one persisted ExecutionRecord by id through a high-level failure analysis application service and receive a structured result.

This slice should establish the Phase 7 seam without trying to solve every classification detail. It should load the execution facts, preserve the ExecutionRecord as immutable factual history, summarize the request/response/assertion facts, and return a basic analysis result that later slices can enrich with classifications, Observations, retry recommendations, and memory feedback.

The entrypoint should be deterministic and should not depend on real LLM calls or external services.

## Acceptance criteria

- [ ] A high-level failure analysis application service can analyze a single ExecutionRecord by execution id.
- [ ] The result includes execution id, task id, case id, overall status, status code where present, duration, environment, request summary, response summary, and failed assertion summary where present.
- [ ] The result handles passed, failed, error, blocked, skipped, and passed-with-warnings records without throwing.
- [ ] Missing ExecutionRecord ids produce a clear validation error.
- [ ] The analysis does not mutate the ExecutionRecord.
- [ ] Tests verify the behavior through the application service seam using persisted ExecutionRecord fixtures.
- [ ] No frontend, controller, report rendering, real LLM, browser automation, service direct invocation, DB direct assertion, or live network dependency is introduced.

## Blocked by

None - can start immediately
