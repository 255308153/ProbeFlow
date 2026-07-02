Status: ready-for-human

# 落地 TestCase、TestCaseStep 与 TestCaseDraft 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add durable storage for formal API test assets and generated drafts. TestCase represents reusable API test assets owned by ApiSpec, SUITE steps are stored as frozen snapshots, and TestCaseDraft represents Task-owned generated material that can later be promoted or discarded.

## Acceptance criteria

- [x] TestCase is persisted as a formal API test asset linked to its primary ApiSpec.
- [x] TestCase supports V1 `caseCategory=API` while keeping FUNCTIONAL only as reserved compatibility where needed.
- [x] TestCase stores detail and SUITE steps as structured JSONB snapshots.
- [x] TestCase includes stale detection fields so future ApiSpec changes can mark assets stale without mutating them.
- [x] TestCaseDraft stores draft content, review status, promotion mode, deterministic deduplication key, and expected status code.
- [x] Repository smoke tests save and load TestCase records with steps.
- [x] Repository smoke tests save and load TestCaseDraft records.
- [x] Migration tests verify tables, JSONB fields, and key indexes.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/02-source-material-and-api-spec-persistence.md`

## Comments

- Added TestCase and TestCaseDraft JPA entities, enums, repositories, and Flyway V4 migrations for PostgreSQL plus the H2 test profile.
- Stored TestCase detail, SUITE steps, tags, preconditions, stale metadata, and draft content as JSONB snapshots.
- Added repository smoke tests and migration tests for the new tables, JSONB fields, and key indexes.
- Verification: `mvn test` passes in `test-agent-backend/` with 14 tests.
