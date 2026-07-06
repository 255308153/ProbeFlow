Status: ready-for-agent

# Issue 04: Report suggestions and prioritized next actions

## Parent

Phase 8: Task Report Generation PRD

## What to build

Add machine-readable report suggestions derived from findings, retryability and failure classifications. Suggestions should tell a tester what to do next after reading the report, such as retry temporary failures, inspect environment or credentials, update stale test cases, or investigate likely API regressions.

Suggestions should be deduplicated and prioritized so the report stays readable and future Agent Loop planning can consume the same structure later.

## Acceptance criteria

- [ ] Reports include structured suggestions with category, priority, action, rationale and supporting source references.
- [ ] Retryable failures produce explicit retry-oriented suggestions.
- [ ] Non-retryable failures produce explicit human-investigation or defect-escalation suggestions.
- [ ] Suggestions are deduplicated by stable keys such as classification, API, case, status code, assertion type, error type and action.
- [ ] Suggestions are sorted deterministically by priority and stable identifiers.
- [ ] Suggestions remain machine-readable for future planner or UI consumption.
- [ ] Tests cover deduplication and priority ordering across repeated similar failures.

## Blocked by

- Issue 03: Structured findings from failure analysis and observations
