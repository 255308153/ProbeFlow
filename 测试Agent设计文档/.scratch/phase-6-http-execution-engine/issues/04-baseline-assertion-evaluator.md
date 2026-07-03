Status: ready-for-agent

# Baseline assertion evaluator

## Parent

`../PRD.md`

## What to build

Add deterministic baseline assertion evaluation for executed HTTP responses. The executor should evaluate the minimum checks needed for Phase 6: expected status code, response body presence, JSON field existence, simple JSON field equality, and duration threshold. Results should be stored structurally in ExecutionRecord.

This slice makes execution results actionable without introducing a full assertion DSL.

## Acceptance criteria

- [ ] Expected status code assertions pass and fail deterministically.
- [ ] Response body presence assertions pass and fail deterministically.
- [ ] JSON field existence assertions pass and fail for simple JSON paths supported by the baseline evaluator.
- [ ] JSON field equality assertions pass and fail for simple scalar values.
- [ ] Duration threshold assertions pass and fail using the captured execution duration.
- [ ] Assertion results are persisted as structured entries with name/type, expected, actual, status, critical flag, and message where useful.
- [ ] Overall status and critical failed flag are derived from assertion results and transport outcome.
- [ ] Tests verify passing assertions, failing assertions, mixed assertion outcomes, malformed JSON behavior, and persistence through the application service seam.
- [ ] The slice does not implement a full assertion DSL, schema validation, contract diffing, batch execution, SUITE execution, reports, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `03-response-capture-execution-snapshot-persistence.md`
