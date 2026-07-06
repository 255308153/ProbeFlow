Status: ready-for-agent

# Issue 06: REGRESSION workflow over existing TestCases

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Implement the deterministic REGRESSION workflow for existing TestCases. A regression task should initialize an execution scope from selected or filtered TestCases, skip source analysis and case generation, then execute the cases, analyze failures where needed and generate a final report. The workflow should surface stale TestCase information before execution when existing data makes it available.

This slice lets ProbeFlow rerun known API tests without treating the task as a fresh exploration.

## Acceptance criteria

- [ ] REGRESSION tasks can be initialized from existing TestCases.
- [ ] REGRESSION templates skip API analysis and test case generation steps.
- [ ] TaskCaseExecution records are prepared for the selected regression cases.
- [ ] Stale TestCase information is surfaced in task metadata, blocker details or report-supporting structured data where available.
- [ ] Regression execution uses the existing HTTP execution service.
- [ ] Regression failures trigger existing failure analysis where needed.
- [ ] Regression workflows generate a final Report.
- [ ] Tests prove regression does not run analysis or generation steps.

## Blocked by

- Issue 01: Task initialization and deterministic template planning
- Issue 02: Orchestration entrypoint and PlanStep execution lifecycle
- Issue 03: Deterministic PlanStep routing to existing services
