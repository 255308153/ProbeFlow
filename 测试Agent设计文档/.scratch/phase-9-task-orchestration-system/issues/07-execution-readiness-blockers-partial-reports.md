Status: ready-for-agent

# Issue 07: Execution readiness blockers and partial reports

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Add execution readiness checks before HTTP execution steps. If required execution inputs such as base URL, environment or auth credentials are missing, the orchestrator should not run HTTP requests. Instead, it should record structured blocker details, update task and step status clearly, and generate a partial report when useful upstream analysis or generation artifacts already exist.

This slice prevents noisy execution failures and gives users actionable next steps.

## Acceptance criteria

- [ ] Execution steps check readiness before invoking HTTP execution.
- [ ] Missing base URL, environment or auth inputs produce structured blocker details.
- [ ] Readiness blockers prevent noisy HTTP execution.
- [ ] Blocked execution status is reflected in Task and PlanStep state.
- [ ] Partial report generation is attempted when useful upstream facts exist.
- [ ] The orchestration result includes blocker details and Report id when a partial report is generated.
- [ ] Tests cover missing execution readiness inputs and partial report behavior.

## Blocked by

- Issue 04: API_TEST automatic workflow from analysis to report
- Issue 06: REGRESSION workflow over existing TestCases
