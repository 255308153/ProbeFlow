Status: ready-for-agent

# Knowledge ingestion entrypoint and revision lifecycle

## Parent

`../PRD.md`

## What to build

Build the first end-to-end Phase 3 knowledge ingestion entrypoint. A caller should be able to submit a Markdown or plain-text knowledge document with metadata, have ProbeFlow create or update the KnowledgeDocument asset, create a KnowledgeDocumentRevision, preserve source content and source hash, and maintain the latest/superseded lifecycle.

This slice establishes the application-service seam that later chunking, metadata enrichment, embedding, and retrieval slices will plug into. It may create a minimal placeholder chunk only if the current model requires it for persistence, but semantic chunking is not required in this slice.

## Acceptance criteria

- [ ] A knowledge ingestion application service accepts title, content, source type, source ref, document type, authority, system, module, business entity, tags, applicable stages, and metadata.
- [ ] Valid Markdown and plain-text inputs create a KnowledgeDocument and a latest KnowledgeDocumentRevision.
- [ ] Re-importing unchanged effective content and metadata is idempotent and does not create duplicate latest revisions.
- [ ] Importing changed content creates a new latest revision and marks the previous latest revision as no longer latest.
- [ ] Older chunks for the same document are marked superseded when a changed revision is imported, where chunks exist.
- [ ] Blank content or unsupported inputs fail clearly without creating partial garbage records.
- [ ] Tests verify document creation, revision lifecycle, idempotency, changed-content update behavior, and failure behavior through the ingestion service seam.
- [ ] The slice does not implement semantic chunking, embedding, retrieval, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

None - can start immediately
