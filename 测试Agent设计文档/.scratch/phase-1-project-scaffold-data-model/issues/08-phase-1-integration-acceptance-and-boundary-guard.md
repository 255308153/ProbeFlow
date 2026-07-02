Status: ready-for-human

# Phase 1 集成验收与边界守护

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the final Phase 1 integration verification that proves the new Test Agent backend foundation is coherent. This slice should validate startup, full Flyway migration, PostgreSQL/pgvector, Redis connectivity, representative repository persistence, and explicit Phase 1 scope boundaries.

## Acceptance criteria

- [x] The application starts in a test profile with required infrastructure available.
- [x] Flyway can apply the full schema from an empty database.
- [x] Tests verify pgvector extension availability.
- [x] Tests verify representative tables and key indexes exist.
- [x] Representative repository save/load paths pass across SourceMaterial, ApiSpec, TestCase, TestCaseDraft, Task, ExecutionRecord, Observation, KnowledgeDocument, KnowledgeChunk, Memory storage, and Report.
- [x] Docker Compose can start the local PostgreSQL/pgvector and Redis infrastructure.
- [x] The project contains no migration or modification work for `AGI-saber-java`.
- [x] The project does not copy ClaudeCode-main or adopt its Bun/TypeScript CLI stack.
- [x] The project does not implement case generation, execution engine behavior, Knowledge RAG retrieval, Memory Refinery, Unified Context Builder, frontend UI, UI automation, Service direct invocation, or DB direct assertions.
- [x] Documentation or test names make the Phase 1 boundary clear for future agents.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/02-source-material-and-api-spec-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/03-test-case-and-draft-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/04-task-orchestration-process-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/05-execution-observation-and-changelog-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/06-knowledge-document-revision-and-chunk-vector-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/07-memory-item-scoped-storage-and-vector-persistence.md`

## Agent notes

- Added `Phase1AcceptanceRepositoryTests` to save and load representative Phase 1 records across the full persistence foundation.
- Added `Phase1BoundaryGuardTests` to verify pgvector/Redis infrastructure declarations, vector migrations, package boundaries, and absence of out-of-scope stacks or copied legacy project code.
- Docker Compose startup remains a local/manual operation; the automated guard verifies the compose file defines PostgreSQL/pgvector and Redis with health checks without requiring externally started containers.
- Verified with `mvn test -Dtest=Phase1AcceptanceRepositoryTests,Phase1BoundaryGuardTests`.
- Verified full backend suite with `mvn test`.
