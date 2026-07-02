Status: ready-for-agent

# 落地 ExecutionRecord、Observation 与 ChangeLog 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add persistence for the execution fact layer, analysis layer, and lightweight asset-change backup layer. ExecutionRecord stores raw HTTP execution facts, Observation stores structured analysis, and ChangeLog stores before/after snapshots for later reversible edits.

## Acceptance criteria

- [ ] ExecutionRecord stores Task/TestCase references, request snapshot, response snapshot, assertion results, timing, and execution status metadata.
- [ ] Observation stores structured analysis linked to Task and ExecutionRecord.
- [ ] Observation includes analysis level for BASIC and DEEP analysis.
- [ ] ExecutionRecord and Observation are modeled as distinct concepts rather than a single merged record.
- [ ] ChangeLog stores entity identity plus before/after JSON snapshots.
- [ ] JSONB snapshot fields can be written and read through repositories.
- [ ] Repository smoke tests save and load ExecutionRecord, Observation, and ChangeLog records.
- [ ] Migration tests verify tables and key indexes.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/04-task-orchestration-process-persistence.md`
