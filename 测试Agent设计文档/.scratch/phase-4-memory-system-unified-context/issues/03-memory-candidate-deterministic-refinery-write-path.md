Status: ready-for-agent

# MemoryCandidate and deterministic Memory Refinery write path

## Parent

`../PRD.md`

## What to build

Build the first Long-term Memory write path through Memory Refinery. A caller should submit a MemoryCandidate derived from Task Memory, Observation, execution summary, user feedback, or manual input. The refinery should deterministically decide whether the candidate is reusable, classify it, compact it, tag it, assign confidence and importance, generate an embedding through the existing embedding boundary, and persist LongTermMemory when appropriate.

This slice makes Memory Refinery the only long-term memory write boundary while keeping the implementation synchronous and testable.

## Acceptance criteria

- [ ] A MemoryCandidate request model captures candidate summary, content, source type, source ref, taskId or scope metadata, tags, confidence, and raw evidence.
- [ ] MemoryRefineryService accepts useful candidates and writes LongTermMemory records.
- [ ] MemoryRefineryService rejects empty, one-off, low-confidence, task-local-only, or non-reusable candidates with a clear result.
- [ ] Useful candidates are classified into existing memory scope types where possible.
- [ ] Useful candidates are compressed into summary/content/fullContent fields without storing raw noise as the main memory.
- [ ] Tags, confidence, importance, source refs, metadata, and embedding are persisted on LongTermMemory.
- [ ] Tests verify accepted candidates, rejected candidates, classification, tagging, confidence/importance assignment, embedding, and persistence through the refinery service seam.
- [ ] The slice does not implement deduplication/merge beyond basic duplicate avoidance, Long-term Memory retrieval, Unified Context Builder, async scheduler, LLM extraction, TestCase generation, HTTP execution, report rendering, or frontend behavior.

## Blocked by

- `01-task-memory-service-task-scoped-fact-lifecycle.md`
