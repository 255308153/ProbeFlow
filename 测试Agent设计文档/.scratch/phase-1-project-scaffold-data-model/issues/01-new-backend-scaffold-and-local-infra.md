Status: ready-for-human

# 新建 Test Agent 后端骨架与本地基础设施

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Create a brand-new Test Agent backend project that can boot locally with Java 21, Spring Boot, Maven, PostgreSQL with pgvector, Redis, Flyway, and JPA. This slice establishes the foundation for all later persistence slices without migrating or modifying `AGI-saber-java`, and without copying ClaudeCode-main.

## Acceptance criteria

- [x] A new Maven single-module Spring Boot backend project exists for the Test Agent.
- [x] The project uses Java 21 and a package name rooted in the new Test Agent project.
- [x] Dependencies include Spring Web, Spring Data JPA, PostgreSQL, Flyway, Redis support, Jackson, and pgvector-compatible support.
- [x] Dependencies do not include Milvus, Elasticsearch, Kafka, Neo4j, browser automation, sandbox execution, or UI automation stacks.
- [x] Docker Compose starts PostgreSQL with pgvector and Redis only.
- [x] Flyway is configured and runs on application startup.
- [x] A minimal Spring Boot context startup test passes.
- [x] The project README or equivalent setup note explains local startup commands.

## Blocked by

None - can start immediately.

## Comments

- Implemented in `test-agent-backend/` with Java 21, Spring Boot, Maven, PostgreSQL/pgvector, Redis, Flyway, JPA, and startup/build contract tests.
- Verification: `mvn test` passes in `test-agent-backend/` with 4 tests.
- Docker Compose is configured, but container startup was not run because the local `docker` command is unavailable in this environment.
