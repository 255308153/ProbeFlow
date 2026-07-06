Status: ready-for-agent

# Issue 08: Immutable report snapshots and regeneration semantics

## Parent

Phase 8: Task Report Generation PRD

## What to build

Finalize report snapshot and regeneration semantics. A persisted Report should represent an immutable structured snapshot of the task facts and analysis available at generation time. Repeated generation with identical inputs should produce stable equivalent content, while generation after new executions should create a newer report snapshot and preserve older reports.

This slice should make report data dependable for future UI, CI and collaboration workflows.

## Acceptance criteria

- [ ] Persisted reports are treated as immutable snapshots.
- [ ] Repeated generation with identical inputs produces stable equivalent content.
- [ ] Regeneration after new executions produces a newer report snapshot.
- [ ] Older reports remain available and are not overwritten by default.
- [ ] Reports include enough generated-at or source-state metadata to reason about staleness.
- [ ] Reports can identify stale analysis or changed execution inputs where existing data supports it.
- [ ] Findings and suggestions ordering remains deterministic.
- [ ] Tests prove deterministic output and snapshot preservation behavior.

## Blocked by

- Issue 02: Execution counts, pass rate, and no-execution states
- Issue 03: Structured findings from failure analysis and observations
- Issue 04: Report suggestions and prioritized next actions
- Issue 05: Failure analysis reuse and missing BASIC analysis handoff
- Issue 06: Suite and mixed execution report coverage
- Issue 07: Memory feedback and learning summary in reports
