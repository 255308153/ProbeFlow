Status: ready-for-agent

# Issue 01: Report generation entrypoint and basic task report snapshot

## Parent

Phase 8: Task Report Generation PRD

## What to build

Create the first end-to-end report generation path for one Task. A caller should be able to invoke a report generation application service with a Task id and receive a result containing the persisted Report id. The generated Report should be a structured immutable snapshot with basic task metadata, environment context where available, total case count, and a clear no-result state when there are no cases or no executions yet.

This slice should establish the Phase 8 application seam without implementing the full reporting intelligence yet. It should reuse the existing Report persistence model and keep report generation isolated from execution and failure analysis responsibilities.

## Acceptance criteria

- [ ] A `ReportGenerationApplicationService` or equivalent highest-level application service can generate a report for one Task.
- [ ] The service persists a Report and returns a result containing the Report id and Task id.
- [ ] The Report contains source task metadata and environment name where the existing model makes it available.
- [ ] The Report includes total case count and a clear state for empty or not-yet-executed tasks.
- [ ] Report fields are structured JSON-backed data where appropriate, not prose-only blobs.
- [ ] Report generation does not mutate Task, TestCase, TaskCaseExecution or ExecutionRecord records.
- [ ] Tests verify the behavior through the application service seam.

## Blocked by

None - can start immediately.
