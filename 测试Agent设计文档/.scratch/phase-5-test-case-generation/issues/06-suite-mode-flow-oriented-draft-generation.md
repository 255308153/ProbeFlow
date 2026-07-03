Status: ready-for-agent

# SUITE mode flow-oriented draft generation

## Parent

`../PRD.md`

## What to build

Add SUITE mode for generating flow-oriented drafts across a related set of ApiSpecs. A caller should be able to pass multiple targets and receive structured TestCaseDraft records that represent a business flow, with steps tied back to individual ApiSpecs.

This slice should reuse the existing generation, context, scenario, dedup, and coverage behavior. It should focus on cross-API flow composition, not execution.

## Acceptance criteria

- [ ] Generation accepts SUITE mode with multiple target ApiSpec ids.
- [ ] The service builds or reuses context for each relevant ApiSpec without bypassing Unified Context Builder semantics.
- [ ] Generated suite drafts include flow-level title, scenario name, module name, preconditions, ordered steps, expected results, and per-step ApiSpec references.
- [ ] Suite steps preserve request shape and expected status hints for each participating ApiSpec.
- [ ] Deduplication works for SUITE drafts and avoids duplicate flow drafts on repeated generation.
- [ ] Coverage summary reports flow-oriented categories separately from single-API categories.
- [ ] Tests verify a related multi-ApiSpec flow creates a coherent suite draft and remains idempotent.
- [ ] The slice does not implement BATCH, promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `05-coverage-summary-incomplete-blocking-diagnostics.md`
