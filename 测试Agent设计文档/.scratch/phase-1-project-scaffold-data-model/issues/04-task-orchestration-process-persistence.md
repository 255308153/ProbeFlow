Status: ready-for-human

# 落地 Task 编排过程对象持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add persistence for the Task process layer. This slice should make it possible to store a Task, its orchestration PlanStep records, its selected TestCase execution references, and its final Report shell without implementing the actual orchestration engine or execution behavior.

## Acceptance criteria

- [x] Task stores the V1 process state needed for generation, review, execution, result analysis, completion, failure, and cancellation.
- [x] Task stores target ApiSpec identifiers and promotion mode.
- [x] Task includes memory refinement tracking fields needed by later phases.
- [x] PlanStep can persist orchestration step metadata without implementing step execution.
- [x] TaskCaseExecution links a Task to formal TestCase assets selected for execution.
- [x] Report can persist a Task final output placeholder or structured summary.
- [x] Repository smoke tests save and load Task, PlanStep, TaskCaseExecution, and Report records.
- [x] Migration tests verify tables and key indexes.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/02-source-material-and-api-spec-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/03-test-case-and-draft-persistence.md`

## Comments

- Added Task, PlanStep, TaskCaseExecution, and Report JPA entities, enums, repositories, and Flyway V5 migrations for PostgreSQL plus the H2 test profile.
- Stored target ApiSpec IDs, Task metadata, execution snapshots, findings, and suggestions as JSONB fields.
- Added minimal Task memory refinement tracking fields required by later phases without adding Memory Refinery behavior.
- Added repository smoke tests and migration tests for the process-layer tables and indexes.
- Verification: `mvn test` passes in `test-agent-backend/` with 19 tests.
