Status: ready-for-agent

# Knowledge metadata extraction and stage tagging

## Parent

`../PRD.md`

## What to build

Add rule-based metadata enrichment to the knowledge ingestion path. Explicit user-provided metadata should remain authoritative, while the system enriches chunks with useful retrieval hints such as API paths, HTTP methods, error codes, tags, business entity clues, and applicable task stages.

This slice should make chunks more retrievable without calling an LLM or introducing external services.

## Acceptance criteria

- [ ] Explicit document and chunk metadata supplied by the caller is preserved and takes priority over inferred metadata.
- [ ] API path hints are extracted from common HTTP path patterns in headings and content.
- [ ] HTTP method hints are extracted when method tokens appear near API paths.
- [ ] Error code hints are extracted from common numeric and symbolic error-code patterns.
- [ ] Tags are derived from explicit metadata, document type, headings, and known keywords such as auth, payment, order, risk, error-code, and test-spec.
- [ ] Applicable stages are derived from document type and explicit caller input for API analysis, case generation, and failure analysis profiles.
- [ ] Tests verify metadata enrichment through ingestion output and persisted chunk metadata.
- [ ] The slice does not implement embedding, retrieval, rerank, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

- `02-markdown-plain-text-semantic-chunking.md`
