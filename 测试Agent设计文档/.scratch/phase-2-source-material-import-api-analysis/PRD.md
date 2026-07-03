Status: ready-for-agent

# Phase 2: SourceMaterial 导入与 ApiSpec 自动分析 PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端工程骨架、数据库迁移、核心领域对象、Repository 和基础验收测试，但系统还不能真正接收用户项目，也不能从源码或 OpenAPI/Swagger 中生成 ApiSpec 接口资产。

从用户角度看，当前系统已经有了“存接口资产”的能力，却还没有“发现接口资产”的能力。用户仍然需要手工创建 ApiSpec，无法把一个 Spring Boot 项目、OpenAPI 文件或 Swagger 文件交给系统后自动得到可用于后续测试用例生成、执行、RAG 上下文构建和任务编排的接口定义。

Phase 2 需要实现第一条真实业务链路：用户提交 SourceMaterial 后，系统能够导入、校验、分析输入物料，并生成或更新 ApiSpec。这个阶段要让 ProbeFlow 从“只有数据地基”进入“可以理解项目接口结构”的状态。

## Solution

实现 SourceMaterial 导入与 ApiSpec 自动分析能力。Phase 2 支持两类输入路径：

1. OpenAPI / Swagger 文件导入。
2. Spring Boot Controller 源码目录或源码包分析。

系统接收 SourceMaterial 后，创建一个 API 分析 Task，记录 PlanStep，执行对应分析器，产出 ApiSpec。ApiSpec 需要包含 HTTP method、path、module、summary、request 参数、request body、response body、validation 线索、auth 线索、sourceType、sourceMaterialId、版本与 readiness 标志。

Phase 2 的目标不是生成测试用例，也不是执行接口请求，而是把“外部输入物料”转成“稳定的接口定义资产”。完成后，后续 Phase 3/4/5 可以基于 ApiSpec 继续实现 RAG、Memory、测试用例生成和执行链路。

推荐的最高测试 seam 是 `ApiAnalysisApplicationService` 或同等应用服务：给定一个 SourceMaterial 输入，调用分析入口，最终断言 SourceMaterial 状态、Task/PlanStep 状态和 ApiSpec 结果，而不是逐个测试内部解析私有方法。

## User Stories

1. As a developer, I want to upload or register a SourceMaterial, so that ProbeFlow can track where API definitions came from.
2. As a developer, I want SourceMaterial to support OpenAPI files, so that existing API documents can be imported without manual transcription.
3. As a developer, I want SourceMaterial to support Swagger files, so that older API documentation formats can still be used.
4. As a developer, I want SourceMaterial to support Spring Boot source directories, so that projects without complete API docs can still be analyzed.
5. As a developer, I want SourceMaterial to support source archives, so that users can submit packaged code instead of a mounted directory.
6. As a developer, I want import validation, so that invalid paths, unsupported formats, and malformed files fail with useful status and error messages.
7. As a developer, I want imported material to be assigned an ingest status, so that users can see whether analysis is pending, running, completed, or failed.
8. As a developer, I want a Task created for each API analysis run, so that the import and analysis process is traceable.
9. As a developer, I want PlanStep records for import, parse, analyze, merge, and completion steps, so that orchestration state can be inspected.
10. As a developer, I want OpenAPI paths converted into ApiSpec records, so that API assets are persisted for later phases.
11. As a developer, I want Swagger definitions converted into ApiSpec records, so that Swagger users get the same asset model as OpenAPI users.
12. As a developer, I want HTTP method and path persisted on ApiSpec, so that interface lookup and deduplication are deterministic.
13. As a developer, I want operation summary and description persisted when available, so that later case generation has human-readable context.
14. As a developer, I want tags or modules mapped to ApiSpec module fields, so that APIs can be grouped by business area.
15. As a developer, I want query parameters imported from OpenAPI, so that request shape is available for later case generation.
16. As a developer, I want path parameters imported from OpenAPI, so that required URL variables are explicit.
17. As a developer, I want header parameters imported from OpenAPI, so that auth and content negotiation hints are preserved.
18. As a developer, I want request body schema imported from OpenAPI, so that JSON payload structure can be used later.
19. As a developer, I want response schema imported from OpenAPI, so that future assertions can reason about expected response shape.
20. As a developer, I want required fields and enum values imported where available, so that validation hints are not lost.
21. As a developer, I want security schemes imported as auth hints, so that future execution and case generation can understand authentication needs.
22. As a developer, I want Spring Boot `@RestController` classes discovered, so that code-first projects can produce ApiSpec assets.
23. As a developer, I want Spring mapping annotations discovered, so that `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, and `@PatchMapping` produce route definitions.
24. As a developer, I want class-level and method-level mappings combined, so that final paths match runtime routes.
25. As a developer, I want Spring method parameters extracted, so that `@RequestParam`, `@PathVariable`, `@RequestHeader`, and `@RequestBody` are represented.
26. As a developer, I want request DTO class names and fields extracted at a basic level, so that ApiSpec can describe body shape.
27. As a developer, I want response DTO class names and fields extracted at a basic level, so that ApiSpec can describe expected response shape.
28. As a developer, I want validation annotations extracted, so that `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Pattern`, and similar constraints become validation hints.
29. As a developer, I want auth annotations extracted, so that `@PreAuthorize` and security-related annotations become auth hints.
30. As a developer, I want ApiSpec readiness flags updated during analysis, so that downstream steps can tell whether route, basic params, DTO, validation, and auth analysis are complete.
31. As a developer, I want partial analysis results saved when possible, so that one malformed controller does not necessarily discard all valid routes.
32. As a developer, I want analysis errors recorded with enough detail, so that users can fix invalid input material.
33. As a developer, I want deterministic ApiSpec deduplication, so that re-importing the same material does not create duplicate API assets.
34. As a developer, I want ApiSpec version updates, so that changed API definitions are reflected without losing history.
35. As a developer, I want unchanged ApiSpec records left untouched, so that repeated imports are idempotent.
36. As a developer, I want changed ApiSpec records to be marked as updated, so that future stale TestCase detection can work.
37. As a developer, I want removed routes detected as absent from the latest SourceMaterial, so that future phases can decide how to mark stale assets.
38. As a developer, I want source metadata preserved, so that every ApiSpec can point back to its SourceMaterial and source location.
39. As a developer, I want line or file location stored for Spring Boot routes when available, so that users can inspect where an API came from.
40. As a developer, I want OpenAPI operationId stored when available, so that stable identifiers are available for matching.
41. As a developer, I want analysis to stay inside V1 HTTP API boundaries, so that UI automation, Service direct invocation, and DB direct assertions are not introduced.
42. As a developer, I want no large-model dependency in Phase 2, so that deterministic parsing and analysis can be tested reliably.
43. As a developer, I want no RAG retrieval in Phase 2, so that Knowledge RAG remains a later phase and ApiSpec analysis remains focused.
44. As a developer, I want no Memory Refinery in Phase 2, so that runtime experience extraction does not appear before execution exists.
45. As a developer, I want no TestCase generation in Phase 2, so that ApiSpec quality can be validated before generating cases.
46. As a developer, I want no HTTP execution engine in Phase 2, so that import and analysis remain a clear bounded slice.
47. As a developer, I want integration tests with fixture OpenAPI files, so that import behavior is validated end-to-end.
48. As a developer, I want integration tests with fixture Spring Boot controllers, so that code analysis behavior is validated end-to-end.
49. As a maintainer, I want the analysis boundary to be explicit, so that later phases do not have to untangle parsing from generation.
50. As a future agent, I want a clear Phase 2 issue set, so that implementation can proceed one slice at a time.

## Implementation Decisions

- Build Phase 2 on top of the existing Phase 1 `test-agent-backend` project.
- Do not create a second backend project.
- Use existing SourceMaterial, ApiSpec, Task, PlanStep, Report, ChangeLog, and Repository foundations from Phase 1.
- Add application-level services for SourceMaterial import and API analysis.
- The highest-level seam should be an API analysis application service that accepts a SourceMaterial request and coordinates validation, Task initialization, parser selection, analysis, ApiSpec upsert, and status updates.
- Support OpenAPI / Swagger import through a deterministic parser library rather than custom ad hoc string parsing.
- Support Spring Boot Controller analysis through structured source parsing where practical. Prefer JavaParser or another AST-based approach over regex-only parsing.
- A lightweight fallback may be used for annotations that are easy to extract, but route extraction should not rely entirely on brittle string matching.
- Keep Phase 2 synchronous enough for deterministic tests. Background execution may be represented by Task/PlanStep statuses, but full async job orchestration is not required.
- Add an analysis request model that can reference an existing SourceMaterial or create a new one from file/path metadata.
- Add supported SourceMaterial input types for OpenAPI, Swagger, source directory, and source archive if not already explicit.
- Add SourceMaterial validation for file existence, supported extension, material type, and readable content.
- Store import failure details on SourceMaterial and/or Task status fields already available from Phase 1.
- Create an API analysis Task for each analysis request using existing Task state values.
- Use PlanStep entries to represent import validation, parser execution, ApiSpec merge, and completion.
- Keep Task creation as a pre-loop initialization pattern consistent with the domain glossary.
- Add parser routing by SourceMaterial type.
- For OpenAPI / Swagger, map each operation to one ApiSpec.
- For Spring Boot Controller source, map each discovered handler method to one ApiSpec.
- Combine class-level and method-level Spring mappings into a normalized path.
- Normalize HTTP methods to the existing HttpMethod enum.
- Normalize API paths so that equivalent routes deduplicate consistently.
- Persist ApiSpec sourceType, sourceMaterialId, path, method, module, summary, description, request parameter structure, request body structure, response body structure, validation hints, auth hints, and source location metadata where the current schema supports it.
- If current ApiSpec schema lacks fields required for source location, operationId, raw extracted model, or analysis metadata, add the minimal migration and entity fields needed.
- Preserve Phase 1 readiness flags and update them accurately:
  - routeReady after path and method extraction.
  - basicParamReady after basic query/path/header parameter extraction.
  - dtoExpanded after DTO or schema structure extraction completes.
  - validationReady after validation constraints are extracted or explicitly determined unavailable.
  - authReady after auth/security hints are extracted or explicitly determined unavailable.
  - knowledgeContextReady remains false in Phase 2 because RAG retrieval is out of scope.
- Do not mark knowledgeContextReady true in Phase 2.
- Model partial analysis explicitly. A SourceMaterial may produce some ApiSpec records and still report warnings.
- ApiSpec upsert should use deterministic identity based on source scope plus method plus normalized path, with operationId as an additional matching hint when present.
- Re-importing identical material should be idempotent.
- Re-importing changed material should update existing ApiSpec version or equivalent version field.
- Do not delete ApiSpec records for missing routes in Phase 2 unless the existing model already supports safe soft deletion. Prefer marking them stale or recording absence metadata if needed.
- Keep request and response structures in JSONB-compatible fields where Phase 1 intended flexible detail storage.
- Keep parser output DTOs separate from JPA entities so parser logic does not depend directly on persistence models.
- Add clear error categories for unsupported material, parse failure, unreadable source, and no APIs found.
- Do not call LLMs for code understanding in Phase 2.
- Do not perform Knowledge RAG retrieval, query rewriting, rerank, chunking, or embedding in Phase 2.
- Do not perform Memory Refinery extraction, memory deduplication, memory recall, or feedback bridge behavior in Phase 2.
- Do not generate TestCase or TestCaseDraft in Phase 2.
- Do not execute HTTP requests in Phase 2.
- Do not build frontend UI in Phase 2.
- Maintain existing Java package style, repository style, migration style, and test style from Phase 1.

## Testing Decisions

- Tests should verify external behavior: given a SourceMaterial or analysis request, the system produces expected SourceMaterial status, Task/PlanStep state, and ApiSpec records.
- Prefer testing through the highest application service seam instead of testing parser private methods.
- Add OpenAPI fixture tests that import a small document with multiple paths, methods, parameters, request body, response body, tags, operationId, and security requirements.
- Add Swagger fixture tests if the chosen parser supports Swagger 2.x directly; otherwise test that Swagger input is rejected with a clear unsupported or parse error until conversion is implemented.
- Add Spring Boot source fixture tests with class-level mapping, method-level mapping, request params, path variables, request body DTO, response DTO, validation annotations, and auth annotation.
- Add idempotency tests: importing the same OpenAPI fixture twice should not create duplicate ApiSpec records.
- Add update tests: changing a fixture operation should update the corresponding ApiSpec and version/updated metadata.
- Add failure tests: malformed OpenAPI file should mark the SourceMaterial or Task as failed and should not create partial garbage ApiSpec records.
- Add partial success tests for Spring Boot analysis if one invalid controller exists beside one valid controller.
- Add readiness tests: OpenAPI and Spring analysis should set routeReady, basicParamReady, dtoExpanded, validationReady, and authReady according to extracted information; knowledgeContextReady should remain false.
- Add repository-level tests only where new schema fields or indexes are introduced.
- Add migration tests for any schema changes beyond Phase 1.
- Reuse Phase 1 testing patterns: Spring Boot integration tests, repository smoke tests, migration checks, and fixture-based assertions.
- Do not test RAG chunking, query rewriting, multi-channel retrieval, rerank, Memory Refinery, case generation, assertion evaluation, or HTTP execution in Phase 2.

## Out of Scope

- Knowledge RAG ingestion, text splitting, embedding, query rewriting, multi-channel retrieval, rerank, and context construction.
- Memory Refinery extraction, deduplication, merge, compression, decay, recall, or feedback bridge behavior.
- TestCase generation.
- TestCaseDraft generation.
- SINGLE, SUITE, or BATCH case generation pipelines.
- DependencyLinker and extractRules generation.
- Assertion suggestion or assertion evaluation.
- HTTP execution engine.
- Report rendering beyond any status fields needed for the analysis Task.
- Frontend UI.
- UI automation.
- Browser automation.
- Service direct invocation.
- DB direct assertions.
- LLM-based code understanding.
- Full async distributed job orchestration.
- Kafka, message queues, Elasticsearch, Milvus, Neo4j, or new vector infrastructure.
- Importing non-Java frameworks such as Express, FastAPI, Django, Gin, or NestJS.
- Full Java type solver correctness across arbitrary enterprise monorepos.

## Further Notes

- Phase 2 should make ProbeFlow able to say: “I can ingest your API definition source and produce ApiSpec assets.”
- Phase 2 is the prerequisite for useful case generation. Without trustworthy ApiSpec, later TestCase generation will be weak.
- Spring Boot source analysis should be pragmatic. The goal is robust V1 route and DTO extraction for common Controller patterns, not perfect Java semantic analysis.
- OpenAPI import should be treated as the most deterministic path and should be implemented before or alongside Spring Boot source analysis.
- `knowledgeContextReady` must remain false because Knowledge RAG is not part of this phase.
- The key product boundary remains HTTP API testing only.
- If implementation discovers Phase 1 schema gaps, add minimal schema changes in Phase 2 rather than reshaping the whole data model.
