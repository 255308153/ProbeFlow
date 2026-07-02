Status: ready-for-human

# 落地 KnowledgeDocument Revision 与 KnowledgeChunk 向量存储闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the Knowledge RAG document storage foundation. KnowledgeDocument represents stable documentation assets, KnowledgeDocumentRevision represents version snapshots, and KnowledgeChunk stores retrievable document fragments with pgvector embeddings for later semantic retrieval.

## Acceptance criteria

- [x] KnowledgeDocument stores document type, title, source type, and source reference.
- [x] KnowledgeDocumentRevision links to KnowledgeDocument and stores version, latest flag, and revision status.
- [x] KnowledgeChunk links to both document and revision.
- [x] KnowledgeChunk stores chunk status, content, metadata needed for later retrieval, and a 1024-dimensional embedding.
- [x] pgvector extension is available in the migration path.
- [x] A vector index exists for KnowledgeChunk embeddings where supported by the chosen migration strategy.
- [x] Repository smoke tests save and load KnowledgeDocument, KnowledgeDocumentRevision, and KnowledgeChunk records.
- [x] Tests verify vector write/read for KnowledgeChunk.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`

## Agent notes

- Added `KnowledgeDocument`, `KnowledgeDocumentRevision`, and `KnowledgeChunk` JPA persistence with repository smoke coverage.
- Added PostgreSQL pgvector migration with `vector(1024)` embedding storage and HNSW cosine index; the H2 test migration uses a compatible `vector` domain for storage-only tests.
- Verified with `mvn test -Dtest=KnowledgeDocumentChunkRepositoryTests,KnowledgeDocumentChunkMigrationTests`.
- Verified full backend suite with `mvn test`.
