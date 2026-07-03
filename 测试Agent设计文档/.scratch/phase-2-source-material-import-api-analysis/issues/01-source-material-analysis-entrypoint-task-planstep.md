Status: ready-for-agent

# SourceMaterial 分析入口与 Task/PlanStep 状态闭环

## Parent

`../PRD.md`

## What to build

Build the first end-to-end Phase 2 analysis entrypoint. A caller should be able to request API analysis for a SourceMaterial, have the system validate the input, create or update SourceMaterial state, create an API analysis Task, record PlanStep progress, and produce a clear completed or failed outcome for unsupported or invalid input.

This slice does not need to parse OpenAPI or Spring Boot source yet. Its purpose is to establish the application-service seam that later slices will plug parsers into.

## Acceptance criteria

- [ ] A single application-level API analysis entrypoint accepts a request that references or creates a SourceMaterial.
- [ ] Valid requests create an API analysis Task and PlanStep records for validation and parser routing.
- [ ] Unsupported material types fail with a clear SourceMaterial and/or Task failure state.
- [ ] Invalid paths or unreadable material fail without creating partial garbage ApiSpec records.
- [ ] SourceMaterial ingest status is updated through pending/running/completed/failed-equivalent states already available in the model, or minimal enum additions are made if needed.
- [ ] Tests verify the external behavior through the application service seam rather than private parser methods.
- [ ] The slice does not implement OpenAPI parsing, Spring Boot parsing, RAG, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

None - can start immediately
