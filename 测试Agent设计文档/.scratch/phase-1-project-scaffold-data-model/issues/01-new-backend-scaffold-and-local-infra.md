Status: ready-for-agent

# 新建 Test Agent 后端骨架与本地基础设施

## Parent

`.scratch/phase-1-project-scaffold-data-model/PRD.md`

## What to build

Create a brand-new Test Agent backend project that can boot locally with Java 21, Spring Boot, Maven, PostgreSQL with pgvector, Redis, Flyway, and JPA. This slice establishes the foundation for all later persistence slices without migrating or modifying `AGI-saber-java`, and without copying ClaudeCode-main.

## Acceptance criteria

- [ ] A new Maven single-module Spring Boot backend project exists for the Test Agent.
- [ ] The project uses Java 21 and a package name rooted in the new Test Agent project.
- [ ] Dependencies include Spring Web, Spring Data JPA, PostgreSQL, Flyway, Redis support, Jackson, and pgvector-compatible support.
- [ ] Dependencies do not include Milvus, Elasticsearch, Kafka, Neo4j, browser automation, sandbox execution, or UI automation stacks.
- [ ] Docker Compose starts PostgreSQL with pgvector and Redis only.
- [ ] Flyway is configured and runs on application startup.
- [ ] A minimal Spring Boot context startup test passes.
- [ ] The project README or equivalent setup note explains local startup commands.

## Blocked by

None - can start immediately.
