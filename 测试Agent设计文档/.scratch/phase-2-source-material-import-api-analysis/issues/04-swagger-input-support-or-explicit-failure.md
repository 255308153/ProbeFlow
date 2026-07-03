Status: ready-for-agent

# Swagger 输入支持或明确失败闭环

## Parent

`../PRD.md`

## What to build

Handle Swagger SourceMaterial explicitly. If the chosen parser supports Swagger 2.x, convert Swagger operations into ApiSpec records through the same analysis pipeline. If not, reject Swagger input with a clear unsupported or parse failure state that is visible on SourceMaterial and Task/PlanStep records.

This slice prevents Swagger files from silently failing or creating invalid ApiSpec data.

## Acceptance criteria

- [ ] Swagger SourceMaterial is routed explicitly by the analysis entrypoint.
- [ ] If Swagger is supported, Swagger operations produce ApiSpec records equivalent to OpenAPI import for method, path, parameters, request/response structures, and auth hints where available.
- [ ] If Swagger is not supported, the system fails with a clear status and error reason without creating ApiSpec records.
- [ ] Task and PlanStep records reflect the support or failure path.
- [ ] Tests cover the selected behavior with a Swagger fixture.
- [ ] The behavior is documented in test names or issue implementation notes so later agents know whether Swagger is supported or intentionally rejected.

## Blocked by

- `01-source-material-analysis-entrypoint-task-planstep.md`
- `02-openapi-import-to-apispec.md`
