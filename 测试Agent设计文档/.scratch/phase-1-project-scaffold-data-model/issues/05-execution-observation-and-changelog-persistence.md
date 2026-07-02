Status: ready-for-human

# 落地 ExecutionRecord、Observation 与 ChangeLog 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add persistence for the execution fact layer, analysis layer, and lightweight asset-change backup layer. ExecutionRecord stores raw HTTP execution facts, Observation stores structured analysis, and ChangeLog stores before/after snapshots for later reversible edits.

## Acceptance criteria

- [x] ExecutionRecord stores Task/TestCase references, request snapshot, response snapshot, assertion results, timing, and execution status metadata.
- [x] Observation stores structured analysis linked to Task and ExecutionRecord.
- [x] Observation includes analysis level for BASIC and DEEP analysis.
- [x] ExecutionRecord and Observation are modeled as distinct concepts rather than a single merged record.
- [x] ChangeLog stores entity identity plus before/after JSON snapshots.
- [x] JSONB snapshot fields can be written and read through repositories.
- [x] Repository smoke tests save and load ExecutionRecord, Observation, and ChangeLog records.
- [x] Migration tests verify tables and key indexes.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/04-task-orchestration-process-persistence.md`

## Agent notes

- Added persistence-only entities and repositories for `ExecutionRecord`, `Observation`, and `ChangeLog`.
- Added Flyway `V6__execution_observation_changelog.sql` migrations for PostgreSQL and the H2 test profile.
- Verified targeted tests with `mvn test -Dtest=ExecutionObservationChangeLogRepositoryTests,ExecutionObservationChangeLogMigrationTests`.
- Verified full backend suite with `mvn test` (25 tests passing).
