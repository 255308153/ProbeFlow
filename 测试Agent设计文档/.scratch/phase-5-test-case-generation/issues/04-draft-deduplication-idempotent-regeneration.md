Status: ready-for-agent

# Draft deduplication and idempotent regeneration

## Parent

`../PRD.md`

## What to build

Make generation safe to rerun. Repeated generation for the same task, ApiSpec, mode, scenario category, expected status, and normalized intent should not create duplicate TestCaseDraft records. Compatible unpromoted drafts may be updated, while promoted or protected assets must not be overwritten.

This slice turns generation from a one-shot append operation into an idempotent workflow that can support retry, re-generation, and later batch processing.

## Acceptance criteria

- [ ] Draft dedup keys are deterministic and derived from stable generation inputs.
- [ ] Re-running the same SINGLE generation does not create duplicate drafts.
- [ ] Compatible unpromoted draft records can be updated when regenerated content improves or changes.
- [ ] Promoted drafts are not overwritten by regeneration.
- [ ] Generation result reports created, updated, skipped, and duplicate-suppressed counts.
- [ ] Dedup behavior works for contract-derived and context-derived scenarios.
- [ ] Tests verify repeated generation idempotency, update behavior, skip behavior, and result counts through the application service seam.
- [ ] The slice does not implement SUITE, BATCH, promotion to formal TestCase, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `03-knowledge-memory-informed-generation-with-citations.md`
