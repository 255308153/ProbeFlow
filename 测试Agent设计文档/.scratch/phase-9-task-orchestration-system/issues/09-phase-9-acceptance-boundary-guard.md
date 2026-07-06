Status: ready-for-agent

# Issue 09: Phase 9 acceptance boundary guard

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Add Phase 9 acceptance boundary tests that keep orchestration inside the V1 deterministic backend scope. Phase 9 should connect existing services with template-driven task workflows. It must not introduce a real LLM Planner, free-form Agent Loop tool selection, frontend UI, REST controller layer, queues, workers, external notifications, ticketing integrations or non-HTTP testing surfaces.

The boundary guard should follow the style of earlier phase boundary tests and protect the project from turning the V1 orchestrator into the future full Agent Loop too early.

## Acceptance criteria

- [ ] Boundary tests prove Phase 9 introduces no real LLM Planner.
- [ ] Boundary tests prove Phase 9 introduces no free-form Agent Loop tool selection.
- [ ] Boundary tests prove Phase 9 introduces no frontend UI.
- [ ] Boundary tests prove Phase 9 introduces no REST controller layer.
- [ ] Boundary tests prove Phase 9 introduces no distributed worker or queue-backed orchestration.
- [ ] Boundary tests prove Phase 9 introduces no external notification or ticket integration.
- [ ] Boundary tests prove Phase 9 introduces no browser or UI automation.
- [ ] Boundary tests prove Phase 9 introduces no service direct invocation or DB direct assertion engine.
- [ ] Boundary tests prove Phase 9 does not become a source clone, unzip or upload subsystem.
- [ ] Boundary tests preserve the V1 boundary: HTTP/HTTPS REST API testing only.

## Blocked by

- Issue 01: Task initialization and deterministic template planning
- Issue 02: Orchestration entrypoint and PlanStep execution lifecycle
- Issue 03: Deterministic PlanStep routing to existing services
- Issue 04: API_TEST automatic workflow from analysis to report
- Issue 05: Semi-automatic review gate and resume flow
- Issue 06: REGRESSION workflow over existing TestCases
- Issue 07: Execution readiness blockers and partial reports
- Issue 08: Failure handling, resume safety, and report completion semantics
