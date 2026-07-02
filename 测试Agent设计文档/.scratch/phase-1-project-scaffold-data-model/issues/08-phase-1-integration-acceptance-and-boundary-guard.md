Status: ready-for-agent

# Phase 1 集成验收与边界守护

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the final Phase 1 integration verification that proves the new Test Agent backend foundation is coherent. This slice should validate startup, full Flyway migration, PostgreSQL/pgvector, Redis connectivity, representative repository persistence, and explicit Phase 1 scope boundaries.

## Acceptance criteria

- [ ] The application starts in a test profile with required infrastructure available.
- [ ] Flyway can apply the full schema from an empty database.
- [ ] Tests verify pgvector extension availability.
- [ ] Tests verify representative tables and key indexes exist.
- [ ] Representative repository save/load paths pass across SourceMaterial, ApiSpec, TestCase, TestCaseDraft, Task, ExecutionRecord, Observation, KnowledgeDocument, KnowledgeChunk, Memory storage, and Report.
- [ ] Docker Compose can start the local PostgreSQL/pgvector and Redis infrastructure.
- [ ] The project contains no migration or modification work for `AGI-saber-java`.
- [ ] The project does not copy ClaudeCode-main or adopt its Bun/TypeScript CLI stack.
- [ ] The project does not implement case generation, execution engine behavior, Knowledge RAG retrieval, Memory Refinery, Unified Context Builder, frontend UI, UI automation, Service direct invocation, or DB direct assertions.
- [ ] Documentation or test names make the Phase 1 boundary clear for future agents.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/02-source-material-and-api-spec-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/03-test-case-and-draft-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/04-task-orchestration-process-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/05-execution-observation-and-changelog-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/06-knowledge-document-revision-and-chunk-vector-persistence.md`
- `.scratch/phase-1-project-scaffold-data-model/issues/07-memory-item-scoped-storage-and-vector-persistence.md`
