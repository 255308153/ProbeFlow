Status: ready-for-agent

# Response capture and execution snapshot persistence

## Parent

`../PRD.md`

## What to build

Standardize how HTTP transport results become ExecutionRecord snapshots. Successful responses, network errors, timeouts, blocked requests, invalid requests, and skipped executions should produce consistent response snapshots, status codes, durations, error messages, and overall statuses.

This slice turns the raw fake-client result into durable execution facts that later assertions, failure analysis, and reporting can consume.

## Acceptance criteria

- [ ] Response snapshots capture status code, selected headers, body excerpt or JSON preview, body size metadata, duration, and error information.
- [ ] Large response bodies are truncated using a deterministic limit.
- [ ] Binary or unsupported response bodies are represented by metadata rather than raw bytes.
- [ ] Network errors and timeouts persist useful error messages and durations.
- [ ] Blocked, skipped, invalid-request, failed, and error outcomes are distinguishable in the execution result and ExecutionRecord.
- [ ] Overall status is derived from transport/request outcome, not set blindly by callers.
- [ ] Tests verify successful response capture, body truncation, binary metadata, timeout handling, network error handling, blocked/skipped/invalid behavior, and persisted snapshots.
- [ ] The slice does not implement baseline assertions, batch execution, SUITE execution, Task Memory handoff, reports, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `01-http-execution-entrypoint-fake-client-single-execution.md`
- `02-executable-request-builder-environment-dry-run.md`
