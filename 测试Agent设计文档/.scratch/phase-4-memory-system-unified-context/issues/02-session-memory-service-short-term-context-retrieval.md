Status: ready-for-agent

# Session Memory service and short-term context retrieval

## Parent

`../PRD.md`

## What to build

Build the short-term Session Memory seam. A caller should be able to write compact session-scoped context such as current goal, recent decisions, recent failure reason, or retry state, then retrieve active items by sessionId for Unified Context Builder.

This slice should preserve the TTL concept and avoid storing raw transcript dumps.

## Acceptance criteria

- [ ] A Session Memory application service accepts sessionId, scope type, summary, content, tags, source type, source ref, confidence, metadata, and TTL.
- [ ] Valid writes persist Session Memory items with sessionId and TTL semantics.
- [ ] Reads by sessionId return active short-term context in deterministic order.
- [ ] Inactive or expired session memory is excluded from default reads where the storage model makes this observable.
- [ ] The service favors compact summaries and does not require raw conversation transcript storage.
- [ ] Tests verify write, read, TTL fields, active filtering, and compact-context behavior through the service seam.
- [ ] The slice remains compatible with test profile behavior even if production Redis is not running.
- [ ] The slice does not implement Task Memory, Memory Refinery, Long-term Memory retrieval, Unified Context Builder, TestCase generation, HTTP execution, report rendering, or frontend behavior.

## Blocked by

None - can start immediately
