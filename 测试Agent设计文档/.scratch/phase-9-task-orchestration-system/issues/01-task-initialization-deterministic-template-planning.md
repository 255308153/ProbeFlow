Status: ready-for-agent

# Issue 01: Task initialization and deterministic template planning

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Create the first deterministic task planning path for Phase 9. A caller should be able to initialize an API_TEST or REGRESSION task and receive persisted Task and PlanStep records that describe the intended V1 workflow. The plan should be explicit, ordered, auditable and based on task type, source type and promotion mode rather than AI decisions.

This slice establishes the initialization and template seams that later orchestration execution will consume.

## Acceptance criteria

- [ ] A task initialization application seam can create or prepare API_TEST tasks.
- [ ] A task initialization application seam can create or prepare REGRESSION tasks.
- [ ] Task metadata is initialized consistently, including target API scope where available.
- [ ] A deterministic template registry maps task type and mode to ordered PlanStep templates.
- [ ] PlanStep records are persisted in deterministic order for initialized tasks.
- [ ] API_TEST and REGRESSION templates are distinct and reflect the V1 workflow boundary.
- [ ] Initialization is testable without invoking analysis, generation, execution, failure analysis or report generation.
- [ ] Tests verify Task and PlanStep persistence through public seams.

## Blocked by

None - can start immediately.
