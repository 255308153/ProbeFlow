Status: ready-for-agent

# Long-term Memory deduplication and merge lifecycle

## Parent

`../PRD.md`

## What to build

Extend Memory Refinery so repeated or highly similar candidates update an existing active LongTermMemory instead of creating unlimited duplicates. The merge should preserve evidence, improve confidence when stronger evidence appears, and retain source traceability.

This slice keeps long-term memory healthy before retrieval starts depending on it.

## Acceptance criteria

- [ ] Memory Refinery detects duplicate or similar candidates using scope type, normalized summary/content, tags, source scope, and metadata hints.
- [ ] Duplicate candidates merge into an existing active LongTermMemory rather than creating a new record.
- [ ] Merge behavior preserves or appends evidence in fullContent or metadata.
- [ ] Merge behavior updates confidence, importance, success contribution, hit/evidence metadata, and timestamps where supported.
- [ ] Non-duplicate candidates still create separate LongTermMemory records.
- [ ] Archived or inactive memories are not silently revived unless the result explicitly records that decision.
- [ ] Tests verify duplicate merge, stronger evidence update, non-duplicate create, and inactive/archive behavior through the refinery service seam.
- [ ] The slice does not implement Long-term Memory retrieval, Unified Context Builder, async scheduler, LLM extraction, TestCase generation, HTTP execution, report rendering, or frontend behavior.

## Blocked by

- `03-memory-candidate-deterministic-refinery-write-path.md`
