Status: ready-for-agent

# Task Memory service and task-scoped fact lifecycle

## Parent

`../PRD.md`

## What to build

Build the first Phase 4 memory write/read seam for task-scoped facts. A caller should be able to write compact Task Memory entries tied to a taskId, then read active, non-expired task facts back in deterministic order for later context construction.

This slice establishes the current-task memory layer. It should preserve source references, lifecycle stage, tags, confidence, and metadata while avoiding noisy duplicate writes.

## Acceptance criteria

- [ ] A Task Memory application service accepts taskId, scope type, summary, content, tags, source type, source ref, confidence, lifecycle stage, metadata, and optional expiry.
- [ ] Valid writes persist Task Memory items and return stable identifiers.
- [ ] Reads by taskId return active, non-expired task facts in deterministic order.
- [ ] Reads can filter by lifecycle stage where useful.
- [ ] Inactive, archived, or expired task memories are excluded from default reads.
- [ ] Repeated equivalent writes are controlled through idempotency or duplicate suppression.
- [ ] Tests verify write, read, filtering, metadata preservation, expiry handling, and duplicate control through the service seam.
- [ ] The slice does not implement Session Memory, Memory Refinery, Long-term Memory retrieval, Unified Context Builder, TestCase generation, HTTP execution, report rendering, or frontend behavior.

## Blocked by

None - can start immediately
