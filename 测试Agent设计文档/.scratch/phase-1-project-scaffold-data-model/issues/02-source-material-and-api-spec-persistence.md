Status: ready-for-human

# 落地 SourceMaterial 与 ApiSpec 持久化闭环

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Add the first domain persistence path for input material and interface definitions. SourceMaterial tracks where project input comes from, and ApiSpec stores HTTP API definition assets from future Spring Boot Controller analysis or OpenAPI/Swagger import. This slice should include schema, typed entities, repositories, and smoke tests.

## Acceptance criteria

- [x] SourceMaterial can represent Git repository, code archive, OpenAPI file, requirement document, and manual selection input types.
- [x] SourceMaterial includes ingest status suitable for pending, ready, and failed ingestion states.
- [x] ApiSpec stores HTTP method, path, system/module ownership, structured parameters, constraints, and auth metadata.
- [x] ApiSpec includes readiness flags for route, basic parameters, DTO expansion, validation, auth, and knowledge context.
- [x] JSONB fields can be written and read through the repository layer.
- [x] Repository smoke tests save and load minimal SourceMaterial and ApiSpec records.
- [x] Migration tests verify the relevant tables and key indexes exist.

## Blocked by

- `.scratch/phase-1-project-scaffold-data-model/issues/01-new-backend-scaffold-and-local-infra.md`

## Comments

- Added SourceMaterial and ApiSpec JPA entities, enums, Spring Data repositories, and Flyway V3 migrations for PostgreSQL plus the H2 test profile.
- Added repository smoke tests for SourceMaterial type/status persistence and ApiSpec HTTP/readiness/JSON metadata persistence.
- Added migration tests for the new tables and lookup/readiness indexes.
- Verification: `mvn test` passes in `test-agent-backend/` with 9 tests.
