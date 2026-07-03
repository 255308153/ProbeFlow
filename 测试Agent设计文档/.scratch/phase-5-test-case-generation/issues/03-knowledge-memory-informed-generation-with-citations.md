Status: ready-for-agent

# Knowledge and Memory informed case generation with citations

## Parent

`../PRD.md`

## What to build

Connect Phase 5 generation to the context intelligence built in Phase 3 and Phase 4. Generated drafts should use the ContextBundle sections returned by Unified Context Builder to create documented business-rule scenarios, known historical failure scenarios, regression-risk scenarios, and review warnings.

This slice should keep RAG knowledge and Memory experience traceable. Draft content and generation results should preserve citations, confidence signals, low-confidence warnings, and context conflict warnings.

## Acceptance criteria

- [ ] Generation uses KnowledgeContext entries from Unified Context Builder to produce business-rule or documentation-derived draft scenarios.
- [ ] Generation uses task/session/long-term memory sections from Unified Context Builder to produce historical failure or regression-risk draft scenarios.
- [ ] Draft content preserves context citations for knowledge chunks and memory items that influenced the draft.
- [ ] Draft metadata distinguishes API contract constraints, Knowledge-derived business constraints, and Memory-derived experience.
- [ ] Low-confidence context is reflected in draft metadata and generation result warnings.
- [ ] Context conflicts are reflected in generation result warnings and are not silently ignored.
- [ ] Generation degrades gracefully when Knowledge is empty, Memory is empty, or both are empty.
- [ ] Tests seed context-rich data and verify generated drafts, citations, confidence warnings, and conflict warnings through the generation application service seam.
- [ ] The slice does not implement dedup updates, SUITE, BATCH, promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `02-deterministic-scenario-planning-contract-negative-boundary-auth.md`
