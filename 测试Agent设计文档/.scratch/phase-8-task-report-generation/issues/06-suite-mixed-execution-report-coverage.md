Status: ready-for-agent

# Issue 06: Suite and mixed execution report coverage

## Parent

Phase 8: Task Report Generation PRD

## What to build

Deepen report support for suite executions and mixed execution modes. Reports should identify the first failing step in a suite, explain downstream skipped steps, and avoid counting dependent skipped steps as independent product failures. The same task report should still summarize SINGLE, BATCH and SUITE execution records together consistently.

This slice focuses on report interpretation and findings for suite flow behavior, not on changing the execution engine.

## Acceptance criteria

- [ ] Suite reports identify the first failing step where the existing execution data supports it.
- [ ] Downstream skipped suite steps are explained as dependent skips rather than independent failures.
- [ ] Suite findings include relevant case, execution and API references.
- [ ] Mixed SINGLE, BATCH and SUITE tasks are summarized in one report consistently.
- [ ] Summary counts remain deterministic when suite and non-suite executions are mixed.
- [ ] Tests cover first-failing-step findings and downstream skipped-step explanations.

## Blocked by

- Issue 03: Structured findings from failure analysis and observations
- Issue 04: Report suggestions and prioritized next actions
