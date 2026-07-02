# ProbeFlow Test Agent

ProbeFlow Test Agent is a V1 design and planning repository for a new HTTP API testing agent platform.

This repository is not an `AGI-saber-java` migration. The product is planned as a brand-new Test Agent backend. Existing projects such as ClaudeCode-main may be used as reference material for agent/task/tool orchestration ideas, but they are not the codebase for this project.

## V1 Scope

V1 focuses only on HTTP API testing:

- Test targets: HTTP/HTTPS REST APIs
- Analysis inputs: Spring Boot Controller code, OpenAPI, or Swagger
- Case category: `API`
- Execution modes: `SINGLE`, `SUITE`, and `BATCH`
- Out of scope: UI automation, Service direct invocation, DB direct assertions, browser interaction, and generic chat-agent behavior

## Repository Layout

- `测试Agent设计文档/CONTEXT.md` - project glossary and V1 domain decisions
- `测试Agent设计文档/PRD/` - canonical module PRDs
- `测试Agent设计文档/*.md` - module design documents
- `测试Agent设计文档/V1-FINAL-BOUNDARY.md` - final V1 boundary notes
- `测试Agent设计文档/docs/agents/` - Matt skills configuration
- `测试Agent设计文档/.scratch/` - local issue tracker used by Matt skills

## Current Phase

Phase 1 is ready for implementation planning:

- PRD: `测试Agent设计文档/.scratch/phase-1-project-scaffold-data-model/PRD.md`
- Issues: `测试Agent设计文档/.scratch/phase-1-project-scaffold-data-model/issues/`

Phase 1 builds a brand-new backend foundation:

- Java 21 + Spring Boot
- Maven single module
- PostgreSQL + pgvector
- Redis
- Flyway migrations
- JPA entities and repositories
- Startup, migration, and repository smoke tests

Phase 1 deliberately does not implement API analysis, case generation, assertion evaluation, execution engine behavior, Knowledge RAG retrieval, Memory Refinery, Unified Context Builder, frontend UI, or any `AGI-saber-java` migration.

## Matt Skills Workflow

This repository is configured for Matt skills:

- `/to-prd` publishes PRDs into `.scratch/<feature>/PRD.md`
- `/to-issues` breaks PRDs into `.scratch/<feature>/issues/<NN>-<slug>.md`
- `/implement` should be run from the actual code project once a specific issue is selected
- `/code-review`, `/tdd`, and `/diagnosing-bugs` can be used during implementation

All generated local tracker items should use:

```text
Status: ready-for-agent
```

## Important Note

This repository currently holds product design, PRDs, and implementation planning artifacts. The actual Test Agent code project should be created separately from scratch, then implemented against the Phase 1 issues.
