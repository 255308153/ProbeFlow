Status: ready-for-agent

# Deterministic scenario planning for contract, negative, boundary, and auth cases

## Parent

`../PRD.md`

## What to build

Extend SINGLE mode from one happy-path draft into deterministic scenario planning. For one ApiSpec, the generator should inspect API contract data and create scenario intents and drafts for parameter constraints, missing required values, invalid values, boundary values, authentication failures, and permission failures where applicable.

This slice should keep the generator local and deterministic. It should turn ApiSpec structure into useful draft coverage without requiring Knowledge, Memory, LLMs, or HTTP execution.

## Acceptance criteria

- [ ] SINGLE mode produces explicit scenario intents before creating drafts.
- [ ] Required parameters can produce missing-required scenarios.
- [ ] Parameter constraints can produce boundary and invalid-value scenarios.
- [ ] Auth metadata can produce authentication failure scenarios.
- [ ] Permission or role hints can produce permission failure scenarios where represented in the available ApiSpec/context data.
- [ ] Generated drafts include scenario category, expected status code, risk/priority hints, request shape, validation hints, and tags.
- [ ] The generation result distinguishes generated, skipped, and unsupported scenario categories.
- [ ] Tests verify scenario planning and draft persistence through the generation application service seam.
- [ ] The slice does not implement Knowledge/Memory-informed scenarios, dedup updates, SUITE, BATCH, promotion, HTTP execution, reports, frontend, or real LLM calls.

## Blocked by

- `01-test-case-generation-entrypoint-single-happy-path-drafts.md`
