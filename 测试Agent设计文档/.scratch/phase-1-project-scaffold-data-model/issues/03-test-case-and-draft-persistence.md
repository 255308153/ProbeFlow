Status: ready-for-agent

# 落地 TestCase、TestCaseStep 与 TestCaseDraft 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add durable storage for formal API test assets and generated drafts. TestCase represents reusable API test assets owned by ApiSpec, SUITE steps are stored as frozen snapshots, and TestCaseDraft represents Task-owned generated material that can later be promoted or discarded.

## Acceptance criteria

- [ ] TestCase is persisted as a formal API test asset linked to its primary ApiSpec.
- [ ] TestCase supports V1 `caseCategory=API` while keeping FUNCTIONAL only as reserved compatibility where needed.
- [ ] TestCase stores detail and SUITE steps as structured JSONB snapshots.
- [ ] TestCase includes stale detection fields so future ApiSpec changes can mark assets stale without mutating them.
- [ ] TestCaseDraft stores draft content, review status, promotion mode, deterministic deduplication key, and expected status code.
- [ ] Repository smoke tests save and load TestCase records with steps.
- [ ] Repository smoke tests save and load TestCaseDraft records.
- [ ] Migration tests verify tables, JSONB fields, and key indexes.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/02-source-material-and-api-spec-persistence.md`
