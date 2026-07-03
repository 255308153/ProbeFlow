Status: ready-for-agent

# Long-term Memory retrieval and stage-aware ranking

## Parent

`../PRD.md`

## What to build

Build the Long-term Memory read path. A caller should be able to ask for reusable memories for a stage and query scope, and ProbeFlow should return active memories ranked by structure match, tag match, vector similarity, importance, confidence, success contribution, hit count, and freshness.

This slice provides the MemoryContext source that Unified Context Builder will consume.

## Acceptance criteria

- [ ] A Long-term Memory retrieval service accepts stage profile, raw query, system/module/API metadata, tags, scope types, limit, and budget hints where practical.
- [ ] Retrieval excludes inactive and archived memories by default.
- [ ] Retrieval supports structure filtering from metadata and source scope where available.
- [ ] Retrieval scores tag matches and exact hints such as error code, auth, payment, tenant, signature, timeout, and preference tags.
- [ ] Retrieval computes vector similarity through the shared embedding boundary and stored memory embeddings.
- [ ] Ranking is stage-aware for case generation, execution preparation, failure analysis, and report generation.
- [ ] Selected memories update hitCount and lastUsedAt.
- [ ] Tests verify filtering, stage-aware ordering, vector similarity, inactive/archive exclusion, hitCount updates, and lastUsedAt updates through the retrieval service seam.
- [ ] The slice does not implement Unified Context Builder, TestCase generation, HTTP execution, report rendering, frontend behavior, or external embedding providers.

## Blocked by

- `03-memory-candidate-deterministic-refinery-write-path.md`
- `04-long-term-memory-deduplication-merge-lifecycle.md`
