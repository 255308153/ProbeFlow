Status: ready-for-agent

# Phase 4 acceptance boundary guard

## Parent

`../PRD.md`

## What to build

Add Phase 4 acceptance and boundary tests proving that Memory System and Unified Context Builder work end to end while preserving the V1 product boundary. The acceptance path should demonstrate Task Memory, Session Memory, Memory Refinery, Long-term Memory retrieval, Knowledge RAG integration, ContextBundle assembly, citations, conflicts, budget behavior, and graceful degradation.

The boundary guard should prevent Phase 4 from accidentally implementing TestCase generation, HTTP execution, report rendering, frontend UI, browser automation, service direct invocation, DB direct assertions, or real external embedding providers.

## Acceptance criteria

- [ ] A Phase 4 acceptance test writes Task Memory and Session Memory, refines a reusable candidate into Long-term Memory, retrieves memory, and builds a ContextBundle for an ApiSpec.
- [ ] The acceptance path includes Phase 3 KnowledgeContext integration.
- [ ] The acceptance path verifies citations for knowledge chunks and memory items.
- [ ] The acceptance path verifies graceful degradation when one context source is empty.
- [ ] The acceptance path verifies conflict or budget behavior introduced in Phase 4.
- [ ] Boundary tests assert that Phase 4 does not introduce TestCase generation, TestCaseDraft generation, HTTP execution, report rendering, frontend UI, browser automation, service direct invocation, DB direct assertions, or real external embedding provider calls.
- [ ] Tests verify external behavior through TaskMemoryService, SessionMemoryService, MemoryRefineryService, Long-term Memory retrieval, and Unified Context Builder seams.
- [ ] Full backend tests pass.

## Blocked by

- `01-task-memory-service-task-scoped-fact-lifecycle.md`
- `02-session-memory-service-short-term-context-retrieval.md`
- `03-memory-candidate-deterministic-refinery-write-path.md`
- `04-long-term-memory-deduplication-merge-lifecycle.md`
- `05-long-term-memory-retrieval-stage-aware-ranking.md`
- `06-unified-context-builder-baseline-bundle.md`
- `07-context-conflict-detection-token-budget-pruning.md`
