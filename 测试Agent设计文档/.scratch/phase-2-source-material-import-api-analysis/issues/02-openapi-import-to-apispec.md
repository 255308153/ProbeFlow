Status: ready-for-agent

# OpenAPI 导入到 ApiSpec 的最小端到端链路

## Parent

`../PRD.md`

## What to build

Build the OpenAPI import path through the Phase 2 analysis entrypoint. Given an OpenAPI fixture SourceMaterial, the system should parse operations and persist ApiSpec assets with method, path, module/tag, summary, request parameters, request body, response body, security/auth hints, and readiness flags.

This should be a complete vertical slice from SourceMaterial analysis request to persisted ApiSpec records and Task/PlanStep completion.

## Acceptance criteria

- [ ] An OpenAPI SourceMaterial can be submitted through the analysis entrypoint.
- [ ] Each OpenAPI operation creates one ApiSpec.
- [ ] ApiSpec records include normalized HTTP method and path.
- [ ] OpenAPI tags or equivalent grouping information populate module/grouping metadata where supported.
- [ ] Operation summary, description, operationId, parameters, request body, response body, required fields, enum values, and security hints are preserved where supported by the current schema.
- [ ] ApiSpec readiness flags are updated: routeReady, basicParamReady, dtoExpanded, validationReady, and authReady should reflect extracted data; knowledgeContextReady remains false.
- [ ] Task and PlanStep records show a successful import/analyze/merge path.
- [ ] Fixture-based tests verify the end-to-end behavior through the application service seam.
- [ ] The slice does not implement Swagger-specific behavior beyond what the OpenAPI parser naturally supports.

## Blocked by

- `01-source-material-analysis-entrypoint-task-planstep.md`
