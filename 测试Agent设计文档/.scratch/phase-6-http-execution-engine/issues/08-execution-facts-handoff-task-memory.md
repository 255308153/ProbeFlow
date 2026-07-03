Status: ready-for-agent

# Execution facts handoff to Task Memory

## Parent

`../PRD.md`

## What to build

Preserve useful execution facts for later failure analysis and memory refinement without mutating TestCase definitions. The executor should update TaskCaseExecution and optionally write compact execution summaries into Task Memory through the existing memory service when doing so fits the current architecture.

This slice prepares Phase 7 failure analysis by making execution outcomes available as task-scoped facts.

## Acceptance criteria

- [ ] Execution does not mutate TestCase definitions, manual-edited flags, lock flags, detail, steps, or expected results.
- [ ] TaskCaseExecution status is updated to reflect the latest execution outcome.
- [ ] TaskCaseExecution links to the latest ExecutionRecord for each task/case execution.
- [ ] A compact execution summary can be written to Task Memory for pass/fail/error outcomes if the existing Task Memory service is available.
- [ ] Task Memory entries preserve task id, case id, execution id, environment, status, error summary, failed assertion summary, and source reference where useful.
- [ ] Memory handoff is skipped gracefully if the execution outcome is dry-run only or has no useful facts.
- [ ] Tests verify TestCase immutability, TaskCaseExecution updates, ExecutionRecord links, Task Memory write behavior, and graceful skip behavior.
- [ ] The slice does not implement Long-term Memory refinement, failure analysis narrative generation, report rendering, frontend, browser/UI automation, service direct invocation, DB direct assertions, or real LLM calls.

## Blocked by

- `04-baseline-assertion-evaluator.md`
