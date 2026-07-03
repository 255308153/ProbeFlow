Status: ready-for-agent

# Phase 3: Knowledge RAG 基础 PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端工程骨架、核心数据模型和知识库持久化表。Phase 2 已经完成 SourceMaterial 导入、OpenAPI / Spring 源码分析和 ApiSpec 资产生成。系统现在能“发现接口资产”，但还不能理解接口背后的业务文档、测试规范、错误码说明、环境约束和接口补充说明。

从用户角度看，ProbeFlow 目前只能根据代码和 OpenAPI 得到接口结构。它还不知道这些接口属于哪个业务流程、有哪些业务前置条件、哪些字段有业务约束、哪些错误码需要重点断言、哪些测试规范必须遵守。后续如果直接进入测试用例生成，Agent 只能生成“结构正确”的用例，很难生成“贴合业务”的用例。

Phase 3 需要实现 Knowledge RAG 基础能力：把人类已经写好的稳定文档导入知识库，经过结构化分割、递归分割、metadata enrich、embedding 和基础检索，最终产出可被后续用例生成、失败分析和 Unified Context Builder 消费的 KnowledgeContext。

这个阶段要严格区分 RAG 和 Memory。RAG 管文档知识，例如业务规则、接口说明、测试规范、错误码说明、环境说明。Memory 管运行经验，例如历史失败模式、用户偏好、测试策略、项目踩坑经验。Phase 3 只实现 Knowledge RAG 基础，不实现 Memory Refinery。

## Solution

实现 Knowledge RAG 基础链路：

```text
Raw Document
-> KnowledgeIngestApplicationService
-> KnowledgeDocument / KnowledgeDocumentRevision
-> KnowledgeChunker
-> KnowledgeMetadataExtractor
-> EmbeddingService
-> KnowledgeChunk
-> KnowledgeRetrievalApplicationService
-> KnowledgeContext
```

导入侧支持 Markdown 和纯文本作为 Phase 3 的最小可用输入。系统接收知识文档内容和文档元数据后，创建或更新 KnowledgeDocument，生成新的 KnowledgeDocumentRevision，将旧 revision 的 chunk 标记为 superseded，然后把最新 revision 切分成可检索的 KnowledgeChunk。

分割策略采用“结构优先 + 递归兜底 + 父子索引预留”。Markdown 优先按标题层级、表格、代码块和列表等结构切分；普通文本采用递归字符分割，按段落、换行、句子、字符长度逐级兜底。Phase 3 可以先落地 parent chunk 形态，并在 metadata 中预留 child chunk、parentChunkId、header path 和 chunk kind 等字段；如果实现成本可控，也可以同时落地 child chunk 检索。

Embedding 通过统一 `EmbeddingService` 抽象完成。Phase 3 默认提供 deterministic fake embedding 或本地 stub embedding，保证测试和开发不依赖外部模型。接口必须为后续 BGE-M3、bge-large-zh-v1.5、M3E-base 或 OpenAI embedding 留出替换空间。BGE 类模型的 query instruction prefix 必须封装在 embedding adapter 内，业务代码不得散落拼接前缀。

检索侧支持基础 KnowledgeQuery：system、module、apiPath、httpMethod、bizEntity、docType、stage、rawQuery 和 tags。Phase 3 需要提供最小可用的多路召回雏形：结构过滤、标签过滤、关键词匹配、向量相似度排序和基础 rerank。输出 KnowledgeContext 时按 businessRules、apiNotes、testSpecs、errorCodeGuides、environmentNotes、citedChunks 分组，并保留引用来源。

完成后，ApiSpec 的 `knowledgeContextReady` 可以在成功构建可用 KnowledgeContext 后被更新为 true；没有匹配知识时不阻塞主流程，但要明确返回空上下文或低覆盖度结果。Knowledge RAG 是强增益，不是单点阻塞。

推荐的测试 seams 是两个最高层应用服务：

- `KnowledgeIngestApplicationService`：给定文档内容和元数据，断言 document、revision、chunk、embedding、状态和幂等更新结果。
- `KnowledgeRetrievalApplicationService`：给定当前 ApiSpec 或 KnowledgeQuery，断言检索结果、rerank 顺序、KnowledgeContext 分组、引用来源和空库降级行为。

## User Stories

1. As a developer, I want to import a Markdown document into ProbeFlow, so that business knowledge can be used by later test generation.
2. As a developer, I want to import a plain text document into ProbeFlow, so that simple requirements and notes can enter the knowledge base.
3. As a developer, I want every imported document to become a KnowledgeDocument, so that the system has a stable document asset.
4. As a developer, I want every import to create a KnowledgeDocumentRevision, so that document updates are traceable.
5. As a developer, I want document updates to mark older chunks as superseded, so that default retrieval uses the latest knowledge.
6. As a developer, I want old chunks to remain stored, so that historical citations can still be traced.
7. As a developer, I want document metadata captured at import time, so that retrieval can filter by system, module, API path, business entity, document type, authority, and source.
8. As a developer, I want document type to distinguish PRD, business flow, API note, test spec, error code guide, FAQ, environment guide, and incident review, so that different task stages can retrieve different knowledge.
9. As a developer, I want authority metadata captured, so that official or manually confirmed documents can rank higher than low-authority notes.
10. As a developer, I want source reference captured, so that every context snippet can point back to its origin.
11. As a developer, I want imported raw content preserved, so that chunking and debugging can be reproduced.
12. As a developer, I want content hashing, so that re-importing unchanged documents does not create unnecessary revisions.
13. As a developer, I want idempotent import behavior, so that repeated runs from an agent do not create duplicate knowledge assets.
14. As a developer, I want Markdown heading hierarchy preserved in chunk metadata, so that short chunks still carry chapter context.
15. As a developer, I want Markdown tables preserved as complete units, so that error code and field definition tables are not split into unusable pieces.
16. As a developer, I want Markdown code blocks preserved as complete units, so that examples and commands are not broken.
17. As a developer, I want lists and numbered steps preserved when possible, so that business procedures remain understandable.
18. As a developer, I want plain text to use recursive splitting, so that paragraphs and sentences remain semantically coherent.
19. As a developer, I want fixed-length splitting used only as a final fallback, so that semantic boundaries are not destroyed too early.
20. As a developer, I want chunk token counts recorded, so that later context builders can respect token budgets.
21. As a developer, I want chunk order recorded, so that nearby context can be reconstructed.
22. As a developer, I want parent chunk metadata, so that later child-to-parent retrieval can return larger context.
23. As a developer, I want child chunk metadata prepared, so that future small-to-big retrieval can be added without reshaping the model.
24. As a developer, I want tags extracted from metadata and content, so that retrieval can filter by auth, payment, order, risk, error-code, and test-spec concepts.
25. As a developer, I want applicable stages recorded on chunks, so that API analysis, case generation, and failure analysis can retrieve different context.
26. As a developer, I want an EmbeddingService abstraction, so that embedding providers can be swapped without changing RAG business logic.
27. As a developer, I want deterministic fake embedding in tests, so that CI does not depend on an external model service.
28. As a developer, I want embedding dimensions validated, so that invalid vectors do not corrupt retrieval.
29. As a developer, I want query embedding to go through the same adapter, so that model-specific instruction prefixes are applied consistently.
30. As a developer, I want BGE query prefix handling hidden behind the adapter, so that business services cannot forget it.
31. As a developer, I want document ingestion to work without a real LLM, so that Phase 3 remains deterministic.
32. As a developer, I want basic metadata extraction to be rule-based first, so that tests are stable.
33. As a developer, I want API paths mentioned in documents detected where practical, so that chunks can be linked to ApiSpec context.
34. As a developer, I want HTTP methods mentioned in documents detected where practical, so that route-specific retrieval is more precise.
35. As a developer, I want business entities detected from metadata and headings where practical, so that domain concepts can be used in retrieval.
36. As a developer, I want error codes detected where practical, so that failure analysis can find relevant explanations later.
37. As a developer, I want KnowledgeQuery to accept raw natural language query text, so that future agent steps can ask for relevant knowledge.
38. As a developer, I want KnowledgeQuery to accept structured filters, so that ApiSpec-driven retrieval can be precise.
39. As a developer, I want retrieval by system and module, so that unrelated project documents do not pollute context.
40. As a developer, I want retrieval by apiPath and httpMethod, so that API-specific notes are prioritized.
41. As a developer, I want retrieval by docType, so that test generation can prefer test specs and business rules.
42. As a developer, I want retrieval by stage, so that API analysis, case generation, and failure analysis use different knowledge profiles.
43. As a developer, I want keyword retrieval, so that exact values like error codes, field names, enum values, and API paths are not missed.
44. As a developer, I want vector retrieval, so that semantically related rules can be found even when wording differs.
45. As a developer, I want structure filtering, so that semantic retrieval operates inside the right project and module scope.
46. As a developer, I want basic rerank, so that relevant, authoritative, fresh, stage-fitting chunks appear first.
47. As a developer, I want duplicate chunks merged in retrieval results, so that context is not filled with repeated text.
48. As a developer, I want retrieval to return citations, so that later Agent output can be grounded in source documents.
49. As a developer, I want KnowledgeContext grouped by business rules, API notes, test specs, error codes, and environment notes, so that downstream prompts can consume structured context.
50. As a developer, I want KnowledgeContext to include confidence and coverage hints, so that downstream planners know whether knowledge is strong or weak.
51. As a developer, I want empty knowledge base retrieval to succeed with an empty context, so that RAG is not a hard blocker.
52. As a developer, I want low-confidence retrieval to be marked, so that downstream generation can avoid pretending weak evidence is certain.
53. As a developer, I want ApiSpec knowledgeContextReady updated only when useful context exists, so that readiness gates remain honest.
54. As a developer, I want knowledgeContextReady to remain false when no relevant context is found, so that later phases know the interface only has code/OpenAPI context.
55. As a developer, I want retrieval profile constants for API analysis, case generation, and failure analysis, so that later phases can reuse them.
56. As a developer, I want ingestion failures to be recorded clearly, so that invalid documents can be fixed.
57. As a developer, I want unsupported document types to fail explicitly, so that agents do not silently ignore material.
58. As a developer, I want document revision status to reflect active, superseded, and failed states, so that operators can inspect knowledge health.
59. As a developer, I want chunk status to reflect active and superseded states, so that retrieval filters are reliable.
60. As a developer, I want no Memory Refinery in Phase 3, so that document knowledge and runtime experience remain separate.
61. As a developer, I want no test case generation in Phase 3, so that RAG quality is validated before it drives cases.
62. As a developer, I want no HTTP execution in Phase 3, so that retrieval can be implemented independently.
63. As a developer, I want no UI in Phase 3, so that backend knowledge behavior lands first.
64. As a maintainer, I want integration tests through ingestion and retrieval services, so that behavior is validated without over-testing private chunking helpers.
65. As a future agent, I want a clear Phase 3 issue set, so that implementation can proceed one vertical slice at a time.

## Implementation Decisions

- Build Phase 3 on top of the existing Phase 1 knowledge persistence model and Phase 2 ApiSpec analysis model.
- Do not create a new backend project.
- Do not replace the existing KnowledgeDocument, KnowledgeDocumentRevision, KnowledgeChunk, repository, migration, and vector converter foundations unless a minimal schema gap is discovered.
- Add a `KnowledgeIngestApplicationService` or equivalent application service as the ingestion boundary.
- Add a `KnowledgeRetrievalApplicationService` or equivalent application service as the retrieval boundary.
- Add request and result models for knowledge ingestion, including title, content, system, module, document type, business entity, source type, source reference, authority, tags, applicable stages, and metadata.
- Add request and result models for knowledge retrieval, including raw query, system, module, apiPath, httpMethod, business entity, document type filters, tags, stage, limit, and token budget.
- Use existing document revision semantics: new content creates a new revision, previous latest revision becomes non-latest, old chunks become superseded, and latest chunks become active.
- Use source hash for idempotency. If content and effective metadata are unchanged, return the existing latest revision instead of creating duplicate chunks.
- Preserve raw content on KnowledgeDocument or revision metadata where the current model supports it.
- Keep ingestion synchronous for Phase 3 so tests can deterministically assert final document, revision, and chunk state.
- Support Markdown and plain text first.
- Treat PDF, Word, web crawling, Notion, Confluence, Git sync, and object storage ingestion as future phases.
- Implement Markdown structure splitting with heading hierarchy metadata.
- Preserve tables, code blocks, and list blocks as coherent chunks when practical.
- Implement recursive character splitting for plain text and oversized Markdown blocks.
- Use a conservative token estimator if no tokenizer exists yet. The estimator must be deterministic and documented through tests.
- Initial chunk target should follow the design direction:
  - child chunk target: 128-256 tokens.
  - child overlap: 20-50 tokens.
  - parent chunk target: 800-1200 tokens.
  - parent overlap: 100-150 tokens.
- If Phase 3 does not physically store both parent and child chunks, it must at least store parent/child-ready metadata so that Small-to-Big can be added without data model churn.
- Store chunk title, content, order, token count, tags, applicable stages, status, embedding, and metadata.
- Add metadata fields such as header path, chunk kind, parentChunkId, apiPath hints, httpMethod hints, errorCode hints, and source offsets where feasible.
- Add a `KnowledgeMetadataExtractor` or equivalent component for rule-based metadata enrichment.
- Metadata extraction should prefer explicit user-provided metadata over inferred metadata.
- Extract API path hints from common HTTP path patterns.
- Extract HTTP method hints from method tokens near paths where practical.
- Extract error code hints from common numeric and symbolic error-code patterns where practical.
- Extract tags from document type, headings, known keywords, and explicit metadata.
- Add an `EmbeddingService` abstraction.
- Add a deterministic fake embedding implementation for tests and local development.
- Keep provider-specific behavior such as BGE query instruction prefixes inside embedding adapter code.
- Validate embedding vector dimension against the configured dimension.
- Default Phase 3 vector dimension should match the existing vector storage shape unless a migration is intentionally added.
- Add a simple similarity function that can run in tests without requiring pgvector-specific SQL if needed.
- Prefer repository methods and database filters for structure filtering.
- Add repository query methods needed for active latest chunks, document scope filtering, and common retrieval filters.
- Implement retrieval in a layered order: structured candidate filtering, keyword scoring, vector scoring, rerank, deduplication, context assembly.
- Basic keyword scoring should handle exact API path, HTTP method, error code, field name, tag, and title/header matches.
- Basic vector scoring should use the configured embedding service and cosine-like similarity.
- Basic rerank should combine semantic score, structure score, keyword score, authority score, freshness score, stage fit score, and optional success contribution when fields exist.
- Rerank weights may start as constants and should be centralized rather than scattered.
- Build a KnowledgeContext result with grouped sections:
  - businessRules.
  - apiNotes.
  - testSpecs.
  - errorCodeGuides.
  - environmentNotes.
  - incidentHints.
  - citedChunks.
- KnowledgeContext entries must include chunkId, documentId, documentRevisionId, title or chunk title, score, evidence type, and source reference when available.
- Empty knowledge base retrieval must return an empty KnowledgeContext, not throw.
- Low-confidence retrieval must be represented explicitly in the result.
- When retrieval is executed for an ApiSpec and useful context is found, update knowledgeContextReady if the current model supports it cleanly.
- Do not mark knowledgeContextReady true just because retrieval ran.
- Keep RAG feedback weight adjustment out of Phase 3 except for preserving fields or metadata that make it possible later.
- Keep Memory Refinery, long-term memory recall, and memory feedback bridge out of Phase 3.
- Maintain the V1 boundary: HTTP API testing only. Do not introduce UI automation, browser automation, service direct invocation, or DB direct assertions.
- Maintain the existing Java package style, Spring service style, repository style, Flyway migration style, and integration test style.

## Testing Decisions

- Tests should verify external behavior, not private implementation details.
- The highest ingestion seam should be `KnowledgeIngestApplicationService` or equivalent.
- The highest retrieval seam should be `KnowledgeRetrievalApplicationService` or equivalent.
- Add ingestion tests for Markdown documents with headings, paragraphs, lists, tables, code blocks, tags, and explicit metadata.
- Add ingestion tests for plain text documents that require recursive splitting.
- Add idempotency tests: importing the same content and metadata twice should not create duplicate revisions or duplicate active chunks.
- Add update tests: importing changed content should create a new latest revision and supersede old chunks.
- Add failure tests: blank content, unsupported document type, invalid metadata, or embedding dimension mismatch should fail clearly.
- Add chunking behavior tests through ingestion results, checking semantic outcomes such as heading metadata, table preservation, token count, chunk order, and active chunk status.
- Add metadata extraction tests through ingestion results, checking API path hints, HTTP method hints, error code hints, tags, and applicable stages.
- Add embedding adapter tests with fake embedding, checking deterministic vector length and query-vs-document path separation.
- Add retrieval tests for exact API path and HTTP method matches.
- Add retrieval tests for keyword-only matches such as error code and field names.
- Add retrieval tests for vector similarity using fake deterministic embeddings.
- Add rerank tests through retrieval results, verifying that authority, stage fit, structure match, and keyword match affect final ordering.
- Add KnowledgeContext assembly tests, checking grouping, citedChunks, scores, source references, and low-confidence flags.
- Add empty repository retrieval tests, checking that the result is empty and non-failing.
- Add ApiSpec integration tests if knowledgeContextReady is updated in Phase 3.
- Add migration and repository tests only when new schema fields or indexes are introduced.
- Reuse existing Phase 1/2 testing patterns: Spring Boot integration tests, repository smoke tests, migration checks, and fixture-based assertions.
- Do not test Memory Refinery, long-term memory recall, TestCase generation, HTTP execution, report generation, UI, browser automation, or external embedding provider calls in Phase 3.

## Out of Scope

- Memory Refinery extraction, candidate judgment, deduplication, merge, compression, decay, recall, and feedback bridge behavior.
- Session Memory behavior beyond existing persistence models.
- Task Memory orchestration behavior beyond existing persistence models.
- Long-term Memory retrieval.
- Unified Context Builder final merge of Task Memory, Knowledge RAG, and Long-term Memory.
- TestCase generation.
- TestCaseDraft generation.
- SINGLE, SUITE, or BATCH generation pipelines.
- HTTP execution engine.
- Failure analysis engine.
- RAG feedback调权闭环.
- Deep LLM-based metadata extraction.
- Query rewriting through an LLM.
- Production BGE-M3 deployment.
- Production OpenAI embedding integration.
- Full BM25 infrastructure if it requires adding a major search service.
- Elasticsearch, Milvus, Neo4j, Kafka, or new distributed infrastructure.
- PDF, Word, Notion, Confluence, web crawler, Git wiki, or object storage ingestion.
- Frontend UI.
- Visual knowledge editor.
- UI automation.
- Browser automation.
- Service direct invocation.
- DB direct assertions.
- Non-HTTP API testing.

## Further Notes

- Phase 3 should make ProbeFlow able to say: “I can ingest your project documents and retrieve grounded business context for an API.”
- Phase 3 is the prerequisite for useful test case generation. Without Knowledge RAG, generated cases will be structurally valid but business-thin.
- RAG is not Memory. RAG stores stable human-written document knowledge. Memory stores runtime experience learned by the Agent.
- Knowledge RAG should degrade gracefully. If the knowledge base is empty or no useful chunks are found, downstream phases should continue with code/OpenAPI context and mark coverage as low.
- The first implementation should be deterministic and testable. External embedding providers can be plugged in after the service boundaries are stable.
- The most important quality bar is citation and traceability: every KnowledgeContext claim should be grounded in document, revision, and chunk identifiers.
- Keep the implementation small enough for agent-driven development: ingestion, chunking, embedding, retrieval, rerank, and context assembly should be split into tracer-bullet issues rather than attempted as one large commit.
