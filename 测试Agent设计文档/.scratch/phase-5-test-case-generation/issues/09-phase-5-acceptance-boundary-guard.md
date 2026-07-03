Status: ready-for-agent

# Phase 5 acceptance boundary guard

## Parent

`../PRD.md`

## What to build

Add Phase 5 acceptance and boundary guard coverage. The project should prove that test case generation works through the intended application service seams while remaining inside the V1 boundary: HTTP/HTTPS REST API test asset generation only, with no execution, report rendering, frontend, browser automation, service direct invocation, DB direct assertions, or real LLM dependency.

This slice locks the phase boundary so future work can build execution and reporting in later phases without smuggling them into Phase 5.

## Acceptance criteria

- [ ] Acceptance tests verify SINGLE, SUITE, BATCH, dedup, coverage diagnostics, and promotion behavior at the application service level.
- [ ] Boundary tests prove Phase 5 does not execute HTTP requests.
- [ ] Boundary tests prove Phase 5 does not evaluate assertions against real responses.
- [ ] Boundary tests prove Phase 5 does not render reports or introduce frontend behavior.
- [ ] Boundary tests prove Phase 5 does not introduce browser automation or UI automation.
- [ ] Boundary tests prove Phase 5 does not introduce service direct invocation or DB direct assertions as generated behavior.
- [ ] Boundary tests prove Phase 5 does not require a real LLM provider, streaming model call, or external model routing.
- [ ] Full test suite passes after Phase 5.

## Blocked by

- `01-test-case-generation-entrypoint-single-happy-path-drafts.md`
- `02-deterministic-scenario-planning-contract-negative-boundary-auth.md`
- `03-knowledge-memory-informed-generation-with-citations.md`
- `04-draft-deduplication-idempotent-regeneration.md`
- `05-coverage-summary-incomplete-blocking-diagnostics.md`
- `06-suite-mode-flow-oriented-draft-generation.md`
- `07-batch-mode-resumable-multi-apispec-generation.md`
- `08-draft-promotion-formal-testcase-provenance-safety.md`
