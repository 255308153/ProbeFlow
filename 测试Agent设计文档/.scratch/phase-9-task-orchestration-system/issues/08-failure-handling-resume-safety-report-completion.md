Status: ready-for-agent

# Issue 08: Failure handling, resume safety, and report completion semantics

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Finalize orchestration behavior for failures, skipped dependent work, resume safety and report completion. The orchestrator should avoid duplicating executions or reports during ordinary resume, handle failed critical steps predictably, record skipped non-critical work, and keep report generation tied to the task lifecycle completion point.

This slice hardens the workflow semantics after the main API_TEST, semi-automatic and REGRESSION paths exist.

## Acceptance criteria

- [ ] Failed critical steps prevent invalid downstream execution.
- [ ] Dependent skipped steps are recorded clearly.
- [ ] Resume does not duplicate completed execution steps.
- [ ] Resume does not duplicate final report generation unless regeneration is explicit.
- [ ] Successful executions skip unnecessary failure analysis.
- [ ] Failed, errored, blocked or warning executions receive analysis before final report generation where needed.
- [ ] Orchestration returns deterministic statuses suitable for future CI integration.
- [ ] Tests cover failure stop behavior, skipped steps, resume safety and report completion semantics.

## Blocked by

- Issue 04: API_TEST automatic workflow from analysis to report
- Issue 05: Semi-automatic review gate and resume flow
- Issue 06: REGRESSION workflow over existing TestCases
- Issue 07: Execution readiness blockers and partial reports
