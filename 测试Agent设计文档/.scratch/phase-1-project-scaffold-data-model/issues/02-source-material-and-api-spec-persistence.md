Status: ready-for-agent

# 落地 SourceMaterial 与 ApiSpec 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the first domain persistence path for input material and interface definitions. SourceMaterial tracks where project input comes from, and ApiSpec stores HTTP API definition assets from future Spring Boot Controller analysis or OpenAPI/Swagger import. This slice should include schema, typed entities, repositories, and smoke tests.

## Acceptance criteria

- [ ] SourceMaterial can represent Git repository, code archive, OpenAPI file, requirement document, and manual selection input types.
- [ ] SourceMaterial includes ingest status suitable for pending, ready, and failed ingestion states.
- [ ] ApiSpec stores HTTP method, path, system/module ownership, structured parameters, constraints, and auth metadata.
- [ ] ApiSpec includes readiness flags for route, basic parameters, DTO expansion, validation, auth, and knowledge context.
- [ ] JSONB fields can be written and read through the repository layer.
- [ ] Repository smoke tests save and load minimal SourceMaterial and ApiSpec records.
- [ ] Migration tests verify the relevant tables and key indexes exist.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`
