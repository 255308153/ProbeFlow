Status: ready-for-agent

# TestCase generation entrypoint and SINGLE happy-path drafts

## Parent

`../PRD.md`

## What to build

Build the first end-to-end Phase 5 generation path. A caller should be able to submit a generation request for one target ApiSpec in SINGLE mode, have the service build context through the existing Unified Context Builder, and persist at least one happy-path TestCaseDraft with structured draft content.

This slice establishes the generation application service seam, request/result vocabulary, deterministic baseline generator, and draft persistence behavior. It should be intentionally narrow: one ApiSpec, one mode, happy-path scenario only.

## Acceptance criteria

- [ ] A generation application service accepts task id, optional session id, target ApiSpec id, generation mode, optional scenario filters, and token/context budget.
- [ ] SINGLE mode resolves one ApiSpec and calls the existing Unified Context Builder rather than assembling context independently.
- [ ] The service generates at least one happy-path TestCaseDraft for a structurally valid ApiSpec.
- [ ] The draft stores structured content including title, description, preconditions, steps, expected result, request shape, expected status, scenario category, tags, and generation metadata.
- [ ] The draft preserves target ApiSpec id, task id, source, stage/status, promotion mode, dedup key, and expected status code where the current model supports them.
- [ ] The generation result reports created draft ids, target ApiSpec ids, generation mode, and basic counts.
- [ ] Tests verify the behavior through the application service seam using seeded Task and ApiSpec data.
- [ ] The slice does not implement negative/boundary/auth scenarios, Knowledge/Memory-informed generation, dedup updates, SUITE, BATCH, promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

None - can start immediately
