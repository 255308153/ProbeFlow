Status: ready-for-agent

# Issue 03: Observation persistence and idempotent analysis

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Make failure analysis produce durable Observation records for meaningful analysis outcomes. A repeated analysis of the same execution in the same mode should reuse or deduplicate prior derived artifacts instead of creating duplicates.

This slice turns transient analysis output into queryable project evidence while preserving ExecutionRecord as immutable execution history.

## Acceptance criteria

- [ ] Failed, error, blocked, and passed-with-warnings executions create Observation records when analyzed.
- [ ] Observation records include task id, execution id, observation type, analysis level, source, risk level, summary, failure reason, and next suggestion where applicable.
- [ ] BASIC deterministic analysis writes Observations with SYSTEM source.
- [ ] Passed executions do not create noisy Observations by default.
- [ ] Skipped dry-run executions are not treated as failures.
- [ ] Re-running analysis for the same execution and same analysis mode does not create duplicate Observations.
- [ ] The analysis result returns created or reused Observation ids.
- [ ] Tests verify persistence, idempotency, risk level assignment, and immutable ExecutionRecord behavior.

## Blocked by

- 02-deterministic-execution-failure-classification.md
