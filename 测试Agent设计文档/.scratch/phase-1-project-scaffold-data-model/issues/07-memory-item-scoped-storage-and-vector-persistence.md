Status: ready-for-agent

# 落地 MemoryItem 分层存储与 LongTermMemory 向量闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the MemoryItem storage foundation while preserving the domain rule that memory is logically unified but physically separated by scope. Session Memory, Task Memory, and LongTermMemory should have storage that matches their lifecycle needs, with LongTermMemory supporting vector embeddings for later recall.

## Acceptance criteria

- [ ] MemoryItem concepts are represented with scope-aware storage for Session Memory, Task Memory, and LongTermMemory.
- [ ] Task Memory is persisted in PostgreSQL rather than Redis.
- [ ] LongTermMemory stores a 1024-dimensional embedding.
- [ ] LongTermMemory has a vector index where supported by the chosen migration strategy.
- [ ] Common memory metadata such as type, tags, content, source, confidence, and lifecycle fields can be persisted.
- [ ] Repository smoke tests save and load scoped memory records.
- [ ] Tests verify vector write/read for LongTermMemory.
- [ ] The implementation does not add Memory Refinery extraction, merge, compression, or recall behavior.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
