Status: ready-for-agent

# Phase 2 集成验收与边界守卫

## Parent

`../PRD.md`

## What to build

Add Phase 2 acceptance tests and boundary guards proving that SourceMaterial import and ApiSpec analysis work end-to-end while Phase 2 stays inside its intended boundary. This slice should verify OpenAPI import, Spring Boot source analysis, idempotency, readiness behavior, and explicit exclusions.

The result should make it safe for later agents to start Phase 3 without accidentally depending on unfinished RAG, Memory, TestCase generation, or HTTP execution behavior.

## Acceptance criteria

- [ ] Acceptance tests prove an OpenAPI SourceMaterial can produce ApiSpec records through the analysis entrypoint.
- [ ] Acceptance tests prove a Spring Boot source SourceMaterial can produce ApiSpec records through the analysis entrypoint.
- [ ] Acceptance tests prove repeated import does not duplicate ApiSpec records.
- [ ] Acceptance tests prove readiness flags are set consistently and knowledgeContextReady remains false.
- [ ] Boundary guard tests confirm Phase 2 does not implement Knowledge RAG ingestion, chunking, query rewriting, multi-channel retrieval, rerank, or context construction.
- [ ] Boundary guard tests confirm Phase 2 does not implement Memory Refinery extraction, deduplication, merge, compression, recall, or feedback bridge behavior.
- [ ] Boundary guard tests confirm Phase 2 does not implement TestCase generation, TestCaseDraft generation, HTTP execution, assertion evaluation, frontend UI, UI automation, Service direct invocation, DB direct assertions, or LLM-based code understanding.
- [ ] All Phase 2 tests pass together with Phase 1 tests.
- [ ] Any minimal schema changes introduced during Phase 2 have migration tests.

## Blocked by

- `02-openapi-import-to-apispec.md`
- `03-apispec-upsert-version-idempotency.md`
- `04-swagger-input-support-or-explicit-failure.md`
- `05-spring-controller-route-analysis-to-apispec.md`
- `06-spring-dto-validation-auth-enrichment.md`
- `07-source-archive-import-partial-failure-handling.md`
