Status: ready-for-agent

# 落地 KnowledgeDocument Revision 与 KnowledgeChunk 向量存储闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the Knowledge RAG document storage foundation. KnowledgeDocument represents stable documentation assets, KnowledgeDocumentRevision represents version snapshots, and KnowledgeChunk stores retrievable document fragments with pgvector embeddings for later semantic retrieval.

## Acceptance criteria

- [ ] KnowledgeDocument stores document type, title, source type, and source reference.
- [ ] KnowledgeDocumentRevision links to KnowledgeDocument and stores version, latest flag, and revision status.
- [ ] KnowledgeChunk links to both document and revision.
- [ ] KnowledgeChunk stores chunk status, content, metadata needed for later retrieval, and a 1024-dimensional embedding.
- [ ] pgvector extension is available in the migration path.
- [ ] A vector index exists for KnowledgeChunk embeddings where supported by the chosen migration strategy.
- [ ] Repository smoke tests save and load KnowledgeDocument, KnowledgeDocumentRevision, and KnowledgeChunk records.
- [ ] Tests verify vector write/read for KnowledgeChunk.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
