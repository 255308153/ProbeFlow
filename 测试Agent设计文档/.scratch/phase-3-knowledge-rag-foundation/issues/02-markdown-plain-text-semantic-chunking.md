Status: ready-for-agent

# Markdown and plain-text semantic chunking

## Parent

`../PRD.md`

## What to build

Extend knowledge ingestion so imported documents are split into semantically useful KnowledgeChunk records. Markdown should be split structure-first using headings, tables, code blocks, lists, and paragraphs. Plain text and oversized Markdown blocks should use recursive splitting that preserves paragraphs and sentences before falling back to fixed-size chunks.

Each chunk should be persisted with stable ordering, token count, active status, source metadata, and parent/child-ready metadata so later Small-to-Big retrieval can be added without reshaping the model.

## Acceptance criteria

- [ ] Markdown ingestion creates active KnowledgeChunk records with preserved heading hierarchy in metadata.
- [ ] Markdown tables, code blocks, and list blocks are not split in ways that destroy their local meaning when they fit within configured limits.
- [ ] Plain text ingestion uses recursive splitting: paragraph, newline, sentence, then fixed-size fallback.
- [ ] Oversized blocks are split deterministically with configured target size and overlap behavior.
- [ ] Chunks include chunk title, chunk order, token count, tags, applicable stages, chunk kind, header path, and parent/child-ready metadata where feasible.
- [ ] Re-importing changed content supersedes old chunks and creates active chunks for the new latest revision.
- [ ] Tests verify chunking behavior through the ingestion service results and persisted chunks, not through private helper methods.
- [ ] The slice does not implement embedding, retrieval, rerank, Memory Refinery, TestCase generation, HTTP execution, or frontend behavior.

## Blocked by

- `01-knowledge-ingestion-entrypoint-revision-lifecycle.md`
