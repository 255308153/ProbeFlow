Status: ready-for-agent

# Draft promotion to formal TestCase with provenance and safety rules

## Parent

`../PRD.md`

## What to build

Implement the review boundary that turns generated TestCaseDraft records into formal TestCase records. Promotion should preserve provenance, fill the existing TestCase fields, respect promotion mode, and protect promoted, locked, or manually edited assets from unsafe overwrite.

This slice completes the Phase 5 lifecycle from generated draft to stable test asset without executing the test.

## Acceptance criteria

- [ ] A promotion service or equivalent application seam promotes selected draft ids into formal TestCase records.
- [ ] Promotion fills category, mode, title, description, preconditions, expected result, priority, risk level, tags, scenario name, module name, status, source, detail type, detail, steps, stale status, based-on ApiSpec versions, generated source ids, and generated timestamp where supported by the existing model.
- [ ] Promotion preserves draft provenance including task id, target ApiSpec id, dedup key, source, context citations, generation metadata, and expected status hints.
- [ ] Promoted draft records are marked with promoted case id or equivalent status.
- [ ] Re-promoting the same draft is idempotent.
- [ ] Locked or manually edited formal cases are not overwritten by regeneration or promotion.
- [ ] Priority and risk level are assigned deterministically from scenario category, auth/risk hints, documented constraints, and memory failure patterns.
- [ ] Tests verify promotion, provenance, idempotency, safety rules, and formal TestCase field mapping through application service seams.
- [ ] The slice does not implement HTTP execution, assertion evaluation, reports, frontend, or real LLM calls.

## Blocked by

- `04-draft-deduplication-idempotent-regeneration.md`
