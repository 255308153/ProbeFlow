Status: ready-for-agent

# Issue 06: Task-level batch failure aggregation

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Add task-level analysis over multiple ExecutionRecords. A caller should be able to analyze a task execution scope and receive a deduplicated failure summary grouped by API, case, status code, classification, retryability, and risk level.

This slice prepares the data shape future reporting and orchestration will consume without implementing report rendering or Agent Loop.

## Acceptance criteria

- [ ] The analysis service can analyze all relevant ExecutionRecords for a task or an explicit subset of execution ids.
- [ ] The task-level result includes aggregate counts by classification, risk level, retryability, and overall status.
- [ ] Similar failures are deduplicated by classification, API/case reference, status code, failed assertion type, and error type where available.
- [ ] Failures are ordered by severity and then deterministic identifiers.
- [ ] The task-level result includes affected case ids and execution ids for each grouped failure.
- [ ] Passed executions are counted but do not dominate the failure summary.
- [ ] Missing linked TestCase or ApiSpec records create data-quality analysis instead of crashing.
- [ ] Tests cover aggregation, deduplication, severity ordering, subset filtering, and missing linked records.

## Blocked by

- 03-observation-persistence-idempotent-analysis.md
- 04-retry-suggestion-next-action-recommendation.md
