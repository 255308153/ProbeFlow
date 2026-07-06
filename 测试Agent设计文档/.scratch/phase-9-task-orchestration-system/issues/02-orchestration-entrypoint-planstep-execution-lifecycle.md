Status: ready-for-agent

# Issue 02: Orchestration entrypoint and PlanStep execution lifecycle

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Create the main task orchestration entrypoint that can load an initialized task, execute eligible PlanSteps in order, persist Task and PlanStep status transitions, and return a structured orchestration result. The service should support resuming from an existing task without rerunning completed steps, stop when a task is cancelled, and mark downstream work clearly when a critical step fails.

This slice focuses on orchestration lifecycle behavior, even if individual PlanStep actions are still simple or stubbed through local runners.

## Acceptance criteria

- [ ] A `TaskOrchestrationApplicationService` or equivalent highest-level seam can run an initialized task.
- [ ] PlanSteps transition through pending, running, succeeded, failed or skipped states as appropriate.
- [ ] Task status reflects the current orchestration phase.
- [ ] Completed PlanSteps are not rerun during ordinary resume.
- [ ] Pending or resumable steps can continue from the correct point.
- [ ] CANCELLED tasks do not execute additional steps.
- [ ] Failed critical steps stop dependent downstream work.
- [ ] The orchestration result includes task id, final status, completed step count and blocker details where available.
- [ ] Tests verify status transitions and resume behavior through the orchestration service.

## Blocked by

- Issue 01: Task initialization and deterministic template planning
