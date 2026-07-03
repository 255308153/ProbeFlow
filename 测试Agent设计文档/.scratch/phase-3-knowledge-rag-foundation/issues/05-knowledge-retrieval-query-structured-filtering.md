Status: ready-for-agent

# Knowledge retrieval query and structured candidate filtering

## Parent

`../PRD.md`

## What to build

Build the first KnowledgeRetrievalApplicationService seam. A caller should be able to submit a KnowledgeQuery with raw query text and structured filters, and ProbeFlow should return active latest chunks from the relevant system, module, API path, HTTP method, document type, stage, tag, and business entity scope.

This slice establishes retrieval as a graceful, non-blocking RAG read path. Empty repositories or no-match filters should return an empty result rather than failing.

## Acceptance criteria

- [ ] A KnowledgeRetrievalApplicationService or equivalent service accepts raw query text plus structured filters.
- [ ] KnowledgeQuery supports system, module, apiPath, httpMethod, bizEntity, docType, stage, tags, limit, and token budget where practical.
- [ ] Retrieval considers only active chunks from latest document revisions by default.
- [ ] Structured filtering narrows candidates by project, module, route, document type, stage, and tags.
- [ ] Empty knowledge base retrieval returns an empty, successful result.
- [ ] No-match retrieval returns an empty, successful result with low or zero coverage.
- [ ] Tests verify filtering and empty-result behavior through the retrieval service seam.
- [ ] The slice does not implement hybrid scoring, rerank, final KnowledgeContext assembly, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

- `01-knowledge-ingestion-entrypoint-revision-lifecycle.md`
- `03-knowledge-metadata-extraction-stage-tagging.md`
- `04-embedding-service-fake-provider.md`
