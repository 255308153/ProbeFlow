Status: ready-for-agent

# Phase 6 acceptance boundary guard

## Parent

`../PRD.md`

## What to build

Add Phase 6 acceptance and boundary guard coverage. The project should prove that HTTP execution works through the intended application service seam while staying inside the V1 boundary: HTTP/HTTPS REST API execution only, no frontend, no report renderer, no browser/UI automation, no service direct invocation, no DB direct assertions, no real LLM dependency, and no mandatory live network dependency in CI.

This slice makes Phase 6 safe for team collaboration and future phases.

## Acceptance criteria

- [ ] Acceptance tests verify SINGLE execution, request preparation, response snapshot persistence, baseline assertions, batch execution, SUITE execution, safety policy, and Task Memory handoff through application service seams.
- [ ] Boundary tests prove Phase 6 does not introduce frontend UI or static/template frontend resources.
- [ ] Boundary tests prove Phase 6 does not introduce report rendering.
- [ ] Boundary tests prove Phase 6 does not introduce browser automation or UI automation dependencies.
- [ ] Boundary tests prove Phase 6 does not introduce service direct invocation or DB direct assertions as execution behavior.
- [ ] Boundary tests prove Phase 6 does not require real LLM providers or model routing.
- [ ] Boundary tests prove CI can run with fake HTTP client and no mandatory live network access.
- [ ] Full test suite passes after Phase 6.

## Blocked by

- `01-http-execution-entrypoint-fake-client-single-execution.md`
- `02-executable-request-builder-environment-dry-run.md`
- `03-response-capture-execution-snapshot-persistence.md`
- `04-baseline-assertion-evaluator.md`
- `05-batch-selected-testcase-execution-summary.md`
- `06-suite-ordered-step-execution.md`
- `07-request-safety-policy-transport-failure-classification.md`
- `08-execution-facts-handoff-task-memory.md`
