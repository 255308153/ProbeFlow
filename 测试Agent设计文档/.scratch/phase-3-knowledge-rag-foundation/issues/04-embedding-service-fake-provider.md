Status: ready-for-agent

# EmbeddingService abstraction with deterministic fake embeddings

## Parent

`../PRD.md`

## What to build

Introduce the embedding boundary for Knowledge RAG. The system should generate embeddings for ingested chunks through an EmbeddingService abstraction and provide a deterministic fake provider for tests and local development. The design should distinguish document embedding from query embedding so provider-specific behavior, such as BGE query instruction prefixes, remains hidden inside the adapter.

This slice should make chunks vector-ready without depending on BGE, OpenAI, or any external model service.

## Acceptance criteria

- [ ] An EmbeddingService abstraction supports document embedding and query embedding paths.
- [ ] A deterministic fake embedding provider is available for tests and local development.
- [ ] Knowledge ingestion stores embeddings on active chunks through the embedding abstraction.
- [ ] Embedding vector dimension is validated against the configured storage dimension.
- [ ] Invalid embedding dimensions fail clearly and do not persist corrupt chunk vectors.
- [ ] Provider-specific query prefix behavior is represented at the adapter boundary, not scattered through business services.
- [ ] Tests verify deterministic embeddings, dimension validation, document/query path separation, and ingestion integration.
- [ ] The slice does not call external embedding providers, implement retrieval, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

- `02-markdown-plain-text-semantic-chunking.md`
