Status: ready-for-agent

# Phase 3 acceptance boundary guard

## Parent

`../PRD.md`

## What to build

Add Phase 3 acceptance and boundary tests that prove the Knowledge RAG foundation works end to end while preserving the product boundary. The tests should demonstrate document ingestion, revision lifecycle, semantic chunking, metadata enrichment, fake embedding, retrieval, rerank, KnowledgeContext assembly, and graceful empty-context behavior.

The same guard should prevent accidental implementation of Memory Refinery, TestCase generation, HTTP execution, UI behavior, or external embedding provider calls inside Phase 3.

## Acceptance criteria

- [ ] A Phase 3 acceptance test imports a realistic Markdown knowledge document and retrieves grounded context for an API.
- [ ] A Phase 3 acceptance test covers revision update behavior and confirms old chunks are superseded.
- [ ] A Phase 3 acceptance test covers empty knowledge base retrieval and confirms it returns a graceful empty context.
- [ ] Boundary tests assert that Phase 3 does not introduce Memory Refinery orchestration, TestCase generation, HTTP execution, frontend UI, browser automation, service direct invocation, or DB direct assertions.
- [ ] Tests verify external behavior through application-service seams rather than private chunker, scorer, or parser internals.
- [ ] Full backend tests pass.
- [ ] The final Phase 3 implementation remains inside the V1 HTTP API testing boundary.

## Blocked by

- `01-knowledge-ingestion-entrypoint-revision-lifecycle.md`
- `02-markdown-plain-text-semantic-chunking.md`
- `03-knowledge-metadata-extraction-stage-tagging.md`
- `04-embedding-service-fake-provider.md`
- `05-knowledge-retrieval-query-structured-filtering.md`
- `06-hybrid-scoring-basic-rerank.md`
- `07-knowledge-context-assembly-apispec-readiness.md`
