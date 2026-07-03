Status: ready-for-agent

# Hybrid scoring and basic rerank

## Parent

`../PRD.md`

## What to build

Extend knowledge retrieval from structured candidate filtering into a minimal hybrid retrieval pipeline. The system should score candidates using exact keyword signals, vector similarity, structure match, document authority, stage fit, freshness, and deduplication before returning top-ranked chunks with scores and match reasons.

This slice should make retrieval useful enough for downstream context assembly without adding Elasticsearch, external BM25 infrastructure, or a production reranker model.

## Acceptance criteria

- [ ] Retrieval computes keyword scores for exact API path, HTTP method, error code, field name, tag, title, and heading matches where available.
- [ ] Retrieval computes vector similarity using the configured embedding service and stored chunk embeddings.
- [ ] Retrieval adds structure, authority, freshness, and stage-fit signals to ranking.
- [ ] Rerank weights are centralized and easy to adjust.
- [ ] Duplicate or near-duplicate chunk results are collapsed or deprioritized so context is not filled with repeats.
- [ ] Results include final score, component score or match reason metadata, and a low-confidence indicator when evidence is weak.
- [ ] Tests verify ranking order changes based on keyword match, vector similarity, authority, structure match, and stage fit.
- [ ] The slice does not implement final grouped KnowledgeContext assembly, Memory Refinery, TestCase generation, HTTP execution, external rerank providers, or frontend behavior.

## Blocked by

- `05-knowledge-retrieval-query-structured-filtering.md`
