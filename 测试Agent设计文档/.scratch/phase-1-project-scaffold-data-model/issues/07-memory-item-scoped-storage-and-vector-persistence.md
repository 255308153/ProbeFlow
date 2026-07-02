Status: ready-for-human

# 落地 MemoryItem 分层存储与 LongTermMemory 向量闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the MemoryItem storage foundation while preserving the domain rule that memory is logically unified but physically separated by scope. Session Memory, Task Memory, and LongTermMemory should have storage that matches their lifecycle needs, with LongTermMemory supporting vector embeddings for later recall.

## Acceptance criteria

- [x] MemoryItem concepts are represented with scope-aware storage for Session Memory, Task Memory, and LongTermMemory.
- [x] Task Memory is persisted in PostgreSQL rather than Redis.
- [x] LongTermMemory stores a 1024-dimensional embedding.
- [x] LongTermMemory has a vector index where supported by the chosen migration strategy.
- [x] Common memory metadata such as type, tags, content, source, confidence, and lifecycle fields can be persisted.
- [x] Repository smoke tests save and load scoped memory records.
- [x] Tests verify vector write/read for LongTermMemory.
- [x] The implementation does not add Memory Refinery extraction, merge, compression, or recall behavior.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`

## Agent notes

- Added Redis-hash `SessionMemoryItem` storage model for TTL-backed session memory without adding runtime behavior.
- Added PostgreSQL `TaskMemoryItem` and `LongTermMemory` persistence with common metadata fields and repository smoke tests.
- Added PostgreSQL pgvector migration for `long_term_memory.embedding vector(1024)` with HNSW cosine index; the H2 test migration keeps storage-only vector compatibility.
- Verified with `mvn test -Dtest=MemoryItemRepositoryTests,MemoryItemMigrationTests`.
- Verified full backend suite with `mvn test`.
