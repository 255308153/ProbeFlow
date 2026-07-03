Status: ready-for-agent

# Batch selected TestCase execution with deterministic summary

## Parent

`../PRD.md`

## What to build

Support executing multiple selected TestCases in deterministic order. A caller should receive per-case results and aggregate counts for passed, failed, error, skipped, blocked, and total executions. Batch behavior should support continue-on-failure and stop-on-critical-failure.

This slice makes Phase 6 useful for small regression runs without introducing distributed workers or async queues.

## Acceptance criteria

- [ ] Execution requests can include multiple selected case ids.
- [ ] Selected cases are executed in deterministic order.
- [ ] Batch results include one result per selected case and aggregate counts.
- [ ] Continue-on-failure mode executes later cases after non-critical failure.
- [ ] Stop-on-critical-failure mode stops later cases after a critical failure and marks skipped cases with reasons.
- [ ] Missing, invalid, blocked, or skipped cases are represented in per-case results without hiding successful cases.
- [ ] TaskCaseExecution and ExecutionRecord persistence remain correct for each executed case.
- [ ] Tests verify deterministic ordering, continue-on-failure, stop-on-critical-failure, skipped cases, partial failures, and aggregate counts.
- [ ] The slice does not implement SUITE ordered step execution, Task Memory handoff, reports, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `04-baseline-assertion-evaluator.md`
