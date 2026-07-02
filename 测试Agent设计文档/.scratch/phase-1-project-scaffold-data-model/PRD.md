Status: ready-for-agent

# Phase 1: 新建测试 Agent 项目脚手架与数据模型 PRD

## Problem Statement

当前仓库已经沉淀了测试 Agent 的 V1 产品边界、领域术语表和 9 份模块 PRD，但还没有一个从零开始实现这些设计的代码项目。用户已经明确决策：不在 `AGI-saber-java` 基础上改造，而是重新开发一个全新的测试 Agent 项目。

旧的聊天式 Agent 项目不应成为本项目的代码基座。测试 Agent 的核心领域是 HTTP API 测试，围绕 SourceMaterial、ApiSpec、TestCase、TestCaseDraft、Task、ExecutionRecord、Observation、KnowledgeDocument、KnowledgeChunk、MemoryItem、Report 等对象构建；它不是聊天 Agent、不是通用 ReAct 沙箱，也不是 UI 自动化系统。

Phase 1 需要先把新项目的工程骨架、基础依赖、数据库 schema、实体模型、Repository 和启动验证打牢，让后续接口分析、规则系统、用例生成、断言系统、执行引擎、知识库和记忆系统可以在稳定的数据层上继续实现。

## Solution

新建一个独立的测试 Agent 后端项目，技术基线为 Java 21、Spring Boot、Maven 单模块、PostgreSQL、pgvector、Redis、Flyway、JPA。Phase 1 只交付项目脚手架和数据模型基础设施，不实现业务链路。

ClaudeCode-main 可以作为参考项目，用于理解 agent 工程中的任务、工具、编排、配置、上下文和本地执行组织方式；但 Phase 1 不复制 ClaudeCode-main 的技术栈，不改造 ClaudeCode-main，也不把其 TypeScript/Bun CLI 架构迁移到本项目。

完成后，应得到一个可以启动、可以连接 PostgreSQL/Redis、可以执行 Flyway migration、可以通过 JPA Repository 对核心对象做基础持久化冒烟测试的新项目。

## User Stories

1. As a developer, I want a brand-new Test Agent backend project, so that the implementation is not constrained by unrelated chat-agent code.
2. As a developer, I want the project to use Java 21 and Spring Boot, so that the backend has a modern, stable Java foundation.
3. As a developer, I want a Maven single-module scaffold, so that early V1 development stays simple and easy to navigate.
4. As a developer, I want PostgreSQL as the primary database, so that core testing assets and execution records have durable relational storage.
5. As a developer, I want pgvector enabled, so that KnowledgeChunk and LongTermMemory embeddings can be stored for later retrieval features.
6. As a developer, I want Redis configured as infrastructure, so that later Session Memory or transient coordination features have a ready runtime dependency.
7. As a developer, I want Flyway migrations, so that database schema is versioned and repeatable.
8. As a developer, I want JPA entities for the core domain objects, so that application code can use typed models instead of raw maps.
9. As a developer, I want Repository interfaces for core entities, so that Phase 2 services can persist and query through stable persistence ports.
10. As a developer, I want SourceMaterial modeled, so that Git repositories, code archives, OpenAPI files, requirement documents, and manual selections can be tracked as input material.
11. As a developer, I want ApiSpec modeled with readiness flags, so that analysis completeness can be represented explicitly before case generation.
12. As a developer, I want TestCase modeled as a durable asset owned by ApiSpec, so that generated API cases can be reused for regression.
13. As a developer, I want TestCaseStep represented for SUITE mode, so that multi-step API chains can later be stored as stable step snapshots.
14. As a developer, I want TestCaseDraft modeled separately from TestCase, so that generated drafts can be reviewed, promoted, or discarded.
15. As a developer, I want Task modeled as a process object, so that generation, review, execution, and reporting can be tracked as one run.
16. As a developer, I want PlanStep modeled, so that orchestration steps can be recorded and inspected later.
17. As a developer, I want TaskCaseExecution modeled, so that a Task can reference the formal TestCase assets it executes.
18. As a developer, I want ExecutionRecord modeled as the raw fact layer, so that HTTP request/response snapshots and assertion results are auditable.
19. As a developer, I want Observation modeled as the analysis layer, so that structured conclusions can attach to ExecutionRecord without rewriting raw facts.
20. As a developer, I want ChangeLog modeled, so that important asset edits can keep a lightweight before-image.
21. As a developer, I want Report modeled, so that each Task can produce a final output artifact.
22. As a developer, I want KnowledgeDocument, KnowledgeDocumentRevision, and KnowledgeChunk modeled, so that Knowledge RAG has a document revision foundation.
23. As a developer, I want MemoryItem storage split by scope where needed, so that Session Memory, Task Memory, and LongTermMemory can follow different lifecycles.
24. As a developer, I want JSONB fields for structured snapshots and flexible details, so that V1 avoids premature table fragmentation.
25. As a developer, I want vector columns for KnowledgeChunk and LongTermMemory, so that semantic retrieval can be added without redesigning storage.
26. As a developer, I want Docker Compose for PostgreSQL with pgvector and Redis, so that the project can run locally with one command.
27. As a developer, I want a minimal application startup test, so that the scaffold proves it can boot.
28. As a developer, I want migration tests, so that schema creation problems are caught early.
29. As a developer, I want Repository smoke tests, so that entity mappings are validated before business logic is added.
30. As a future agent, I want the Phase 1 boundary to be explicit, so that I do not accidentally implement case generation, execution, or RAG logic too early.
31. As a maintainer, I want ClaudeCode-main treated as reference material only, so that the new Java backend keeps its own clean architecture.
32. As a maintainer, I want no AGI-saber migration work in Phase 1, so that development focuses on the new Test Agent product.

## Implementation Decisions

- Create a new backend project for the Test Agent. Do not modify or migrate `AGI-saber-java`.
- Use Java 21.
- Use Spring Boot as the backend framework.
- Use Maven single-module structure for V1.
- Use PostgreSQL as the primary database.
- Enable pgvector in PostgreSQL for embedding storage.
- Use Redis as a configured infrastructure dependency, but do not implement business-specific Redis read/write logic in Phase 1.
- Use Flyway for database migrations.
- Use Spring Data JPA for persistence.
- Use Jackson for JSON serialization.
- Use JSONB for flexible structured fields such as ApiSpec parameters, TestCase detail, TestCase steps, request snapshots, response snapshots, assertion results, draft content, and ChangeLog snapshots.
- Use a JSONB mapping approach that keeps application code typed enough for V1 while avoiding premature deep relational modeling.
- Use pgvector Java support or a compatible mapping approach for vector columns.
- Use Docker Compose with only PostgreSQL + pgvector and Redis for local infrastructure.
- Do not add Milvus, Elasticsearch, Kafka, Neo4j, browser automation, sandbox execution, or UI automation dependencies in Phase 1.
- Use package naming rooted in the new Test Agent project, not inherited names from unrelated projects.
- Organize code around domain model, repository, configuration, and application bootstrap.
- Keep business services minimal or absent in Phase 1; the goal is infrastructure and persistence readiness.
- Model SourceMaterial as the input material record for Git repo, code archive, OpenAPI file, requirement document, and manual selection sources.
- Model ApiSpec as the interface definition asset from Spring Boot Controller analysis or OpenAPI/Swagger import.
- Add ApiSpec readiness flags: routeReady, basicParamReady, dtoExpanded, validationReady, authReady, and knowledgeContextReady.
- Model TestCase as the formal reusable test asset owned by ApiSpec.
- Keep V1 TestCase focused on `caseCategory=API`; FUNCTIONAL remains reserved and has no V1 generation or execution path.
- Store TestCase steps as a snapshot for SUITE mode, not as runtime references to SINGLE TestCase.
- Add stale detection fields to TestCase so future ApiSpec or source-case changes can mark a case as stale without silently mutating it.
- Model TestCaseDraft as a Task-owned generated draft with status values for pending review, promoted, and discarded.
- Add promotionMode to distinguish AUTO and MANUAL draft promotion.
- Add deterministic deduplication support to TestCaseDraft.
- Add expectedStatusCode to TestCaseDraft.
- Model Task as the process aggregate for generation, review, execution, analysis, and report production.
- Model Task state around the V1 state machine: PENDING, ANALYZING, CASE_GENERATED, WAITING_FOR_REVIEW, EXECUTING, ANALYZING_RESULTS, COMPLETED, FAILED, CANCELLED.
- Model PlanStep as the durable representation of orchestration steps.
- Keep StepOutcome distinct from Observation. StepOutcome is runtime orchestration output; Observation is persisted analysis.
- Model TaskCaseExecution as the link between a Task and the formal TestCase assets selected for execution.
- Model ExecutionRecord as the fact layer for HTTP request, response, timing, status, and assertion result snapshots.
- Model Observation as the analysis layer attached to ExecutionRecord.
- Add analysisLevel to Observation for BASIC and DEEP analysis.
- Model ChangeLog as lightweight before/after snapshot storage for important asset edits.
- Model Report as the Task final output.
- Model KnowledgeDocument, KnowledgeDocumentRevision, and KnowledgeChunk to support Knowledge RAG document revision.
- Store KnowledgeChunk embedding with vector dimension 1024 for bge-m3 compatibility.
- Model MemoryItem as a unified memory abstraction while allowing physical storage by scope: Session Memory, Task Memory, and LongTermMemory.
- Store LongTermMemory embedding with vector dimension 1024.
- Use indexes for common lookup paths: ApiSpec by module/path, TestCase by primaryApiSpecId and staleStatus, Task by status and createdAt, ExecutionRecord by task/case/time, Observation by task/execution, TestCaseDraft by task/dedupKey, KnowledgeChunk and LongTermMemory by vector column.
- Use ClaudeCode-main only as reference for concepts such as task abstraction, tool abstraction, configuration organization, local execution ergonomics, and agent orchestration boundaries.
- Do not copy ClaudeCode-main implementation wholesale.
- Do not adopt ClaudeCode-main's Bun/TypeScript CLI stack for this Java backend.

## Testing Decisions

- The highest testing seam for Phase 1 is application startup plus persistence infrastructure: the new project should boot, connect to PostgreSQL/Redis, run migrations, and allow basic repository persistence.
- Tests should verify external behavior rather than implementation details.
- Add a Spring Boot context startup test.
- Add Flyway migration tests that run against PostgreSQL with pgvector available.
- Use Testcontainers for PostgreSQL + pgvector in automated tests where practical.
- Test that pgvector extension is enabled.
- Test that all core tables are created by migrations.
- Test that key indexes are present where feasible.
- Test that core JPA entities can save and load a minimal valid record.
- Test JSONB serialization/deserialization for representative fields.
- Test vector column write/read for KnowledgeChunk and LongTermMemory.
- Test Repository smoke paths for SourceMaterial, ApiSpec, TestCase, TestCaseDraft, Task, ExecutionRecord, Observation, KnowledgeDocument, KnowledgeDocumentRevision, KnowledgeChunk, MemoryItem or its scoped tables, and Report.
- Docker Compose verification may be manual or a lightweight smoke command; automated tests should not depend on manually started containers.
- Do not test case generation, execution engine behavior, assertion evaluation, Knowledge RAG retrieval, Memory Refinery extraction, or Unified Context Builder behavior in Phase 1.

## Out of Scope

- Modifying or migrating `AGI-saber-java`.
- Copying ClaudeCode-main into the new project.
- Building a CLI or terminal UI like ClaudeCode-main.
- Implementing Spring Boot Controller code analysis.
- Implementing OpenAPI/Swagger import.
- Implementing StructureCaseGenerator, BusinessCaseGenerator, MemoryCaseEnhancer, AssertionSuggestionGenerator, or CaseNormalizer.
- Implementing SuiteCaseGenerationPipeline or DependencyLinker.
- Implementing HTTP execution engine behavior.
- Implementing assertion evaluation.
- Implementing Knowledge RAG retrieval, reranking, or context construction.
- Implementing Memory Refinery extraction, deduplication, merge, compression, or recall.
- Implementing Unified Context Builder.
- Implementing report rendering beyond the Report persistence model.
- Implementing frontend UI.
- Implementing UI automation, Service direct invocation, DB direct assertions, or browser interaction.
- Adding distributed locks, message queues, Kafka, Elasticsearch, Neo4j, or Milvus.

## Further Notes

- V1 product boundary remains HTTP API testing only.
- SINGLE means a single HTTP interface case.
- SUITE means multiple HTTP interfaces in ordered steps.
- BATCH means multiple HTTP interface cases executed in batch or concurrently.
- FUNCTIONAL is reserved for V2 and must not drive Phase 1 schema or logic beyond compatibility fields explicitly chosen in the PRDs.
- `TestCase.steps[]` for SUITE should be treated as a frozen snapshot once promoted, not a live reference to underlying SINGLE cases.
- `ExecutionRecord` and `Observation` are intentionally separate: facts first, analysis second.
- Phase 1 should produce a boring, reliable foundation. The clever parts belong in later phases.
