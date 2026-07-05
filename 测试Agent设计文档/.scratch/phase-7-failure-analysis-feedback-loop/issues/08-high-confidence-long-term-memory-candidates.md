Status: ready-for-agent

# Issue 08: High-confidence long-term memory candidates

## Parent

Phase 7: Failure Analysis and Feedback Loop PRD

## What to build

Promote only high-confidence, reusable execution findings into the existing Memory Refinery flow. Phase 7 should create long-term memory candidates for repeated or important failure patterns, environment gotchas, auth quirks, validation behavior, and regression risks while rejecting low-value noise.

MemoryRefineryService must remain the only Long-term Memory write boundary.

## Acceptance criteria

- [ ] Repeated task-level failures can produce high-confidence MemoryCandidateRequests.
- [ ] Single severe failures can produce a candidate when the analysis evidence is strong enough.
- [ ] Auth quirks, environment issues, validation behavior, regression risks, and historical failure patterns are tagged appropriately.
- [ ] Low-confidence, one-off, skipped, dry-run, and noisy passed results do not become long-term memory candidates.
- [ ] The analysis result includes whether a memory candidate was accepted, rejected, created, or merged by the refinery.
- [ ] Candidate metadata references execution ids, case ids, classification, risk level, retryability, and source evidence.
- [ ] Tests verify accepted candidates, rejected candidates, merge behavior through the existing refinery, and no direct writes bypassing MemoryRefineryService.

## Blocked by

- 06-task-level-batch-failure-aggregation.md
- 07-execution-feedback-task-memory.md
