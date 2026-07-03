Status: ready-for-agent

# Unified Context Builder baseline bundle

## Parent

`../PRD.md`

## What to build

Build the first Unified Context Builder read seam. A caller should provide taskId, sessionId, ApiSpec or apiSpecId, stage profile, query, and token budget. The builder should assemble a structured ContextBundle from ApiSpec/code context, Task state, Session Memory, Task Memory, Phase 3 KnowledgeContext, and Long-term Memory retrieval.

This slice creates the stable context contract that later case generation, execution preparation, failure analysis, and reporting will consume.

## Acceptance criteria

- [ ] Unified Context Builder accepts taskId, sessionId, ApiSpec or apiSpecId, stage profile, raw query, structured hints, and token budget.
- [ ] ContextBundle is a structured object rather than a single concatenated prompt string.
- [ ] ContextBundle includes apiContext, taskState, sessionContext, taskMemory, knowledgeContext, longTermMemoryContext, constraints, citations, coverage, and budget metadata where available.
- [ ] The builder uses Phase 3 KnowledgeRetrievalApplicationService instead of duplicating RAG retrieval logic.
- [ ] The builder uses Long-term Memory retrieval instead of reading all memories blindly.
- [ ] Empty Session Memory, Task Memory, Knowledge, or Long-term Memory sources degrade gracefully and are reflected in coverage.
- [ ] Citations include knowledge chunk and memory item source identifiers where available.
- [ ] Tests verify sectioned output, source inclusion, citations, graceful empty-source behavior, and deterministic ordering through the builder seam.
- [ ] The slice does not implement conflict detection, budget pruning beyond baseline metadata, TestCase generation, HTTP execution, report rendering, frontend behavior, or external providers.

## Blocked by

- `01-task-memory-service-task-scoped-fact-lifecycle.md`
- `02-session-memory-service-short-term-context-retrieval.md`
- `05-long-term-memory-retrieval-stage-aware-ranking.md`
