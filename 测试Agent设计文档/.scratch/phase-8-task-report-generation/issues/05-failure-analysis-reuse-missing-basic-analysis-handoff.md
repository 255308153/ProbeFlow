Status: ready-for-agent

# Issue 05: Failure analysis reuse and missing BASIC analysis handoff

## Parent

Phase 8: Task Report Generation PRD

## What to build

Make report generation reuse existing Phase 7 failure analysis and observations when they already exist, and trigger only missing BASIC deterministic analysis when report quality requires it. This keeps reports useful for executions that have not yet been analyzed while preserving Phase 8's deterministic backend boundary.

Report generation must not introduce DEEP AI analysis, real LLM dependencies, or duplicate analysis pipelines. It should also avoid mutating factual execution history or test assets.

## Acceptance criteria

- [ ] Report generation reuses existing FailureAnalysis and Observation records when present.
- [ ] Report generation can trigger missing BASIC deterministic failure analysis only when necessary.
- [ ] Report generation does not trigger DEEP AI analysis or any real LLM dependency.
- [ ] Report generation does not mutate ExecutionRecord, TestCase, TestCaseDraft, Observation or Memory records.
- [ ] Reports can indicate stale or missing analysis when executions changed or analysis coverage is incomplete.
- [ ] Tests prove existing observations are reused rather than duplicated.
- [ ] Tests prove missing BASIC analysis is triggered only for eligible missing analysis cases.
- [ ] Tests prove no real LLM dependency is required in CI.

## Blocked by

- Issue 03: Structured findings from failure analysis and observations
- Issue 04: Report suggestions and prioritized next actions
