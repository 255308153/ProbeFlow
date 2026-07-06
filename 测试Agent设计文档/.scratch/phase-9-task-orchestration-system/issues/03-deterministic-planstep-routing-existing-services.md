Status: ready-for-agent

# Issue 03: Deterministic PlanStep routing to existing services

## Parent

Phase 9: Task Orchestration System PRD

## What to build

Add deterministic PlanStep routing so orchestration can call existing application services through a single runner or adapter seam. PlanStepType should map explicitly to existing API analysis, context, case generation, HTTP execution, failure analysis and report generation services. The routing layer should return a runtime StepOutcome or equivalent result object without persisting it as an Observation.

This slice makes orchestration a connector between modules, not a duplicate implementation of module logic.

## Acceptance criteria

- [ ] PlanStepType values are routed through an explicit local mapping.
- [ ] The runner calls existing application services through public seams where supported.
- [ ] The routing layer returns structured runtime outcomes with status, summary and relevant ids.
- [ ] StepOutcome or equivalent runtime results remain distinct from persistent Observation.
- [ ] The orchestrator does not duplicate API analysis, test generation, HTTP execution, failure analysis or report generation logic.
- [ ] The routing design leaves a future planner extension point without implementing real LLM planning.
- [ ] Tests prove routing is deterministic and does not depend on AI decisions.

## Blocked by

- Issue 02: Orchestration entrypoint and PlanStep execution lifecycle
