Status: ready-for-agent

# Phase 4: Memory System 与 Unified Context Builder PRD

## Problem Statement

Phase 1 已经完成 ProbeFlow 后端工程骨架、核心领域对象和 Memory 持久化模型。Phase 2 已经完成 SourceMaterial 导入和 ApiSpec 自动分析。Phase 3 已经完成 Knowledge RAG 基础：知识文档导入、语义分块、metadata enrich、fake embedding、结构化检索、hybrid ranking、KnowledgeContext 组装和 ApiSpec knowledgeContextReady 更新。

系统现在能回答两个问题：

- 这个项目有哪些接口？
- 这个接口相关的人类文档知识是什么？

但系统还不能回答第三个关键问题：

- Agent 在运行中已经学到了什么？当前任务、当前会话、历史任务经验和知识库上下文应该怎样合并给下一步使用？

从用户角度看，ProbeFlow 现在已经具备“接口资产”和“文档知识”，但仍然缺少运行经验层。后续测试用例生成、执行前准备、失败分析和报告生成如果没有 Memory 与 Unified Context Builder，就会出现几个问题：

- 同一个任务内已经发生过的事实不能稳定传递给后续步骤。
- 历史失败模式、项目特殊约定和用户偏好不能被后续任务复用。
- Knowledge RAG 与 Memory 的职责边界不清，容易把文档知识和运行经验混在一起。
- 下游模块需要自己拼 ApiSpec、KnowledgeContext、Task 状态、Session Memory、Task Memory、Long-term Memory，导致上下文来源不统一、引用不可追踪、token 预算不可控。

Phase 4 需要实现 Memory System 与 Unified Context Builder，让 ProbeFlow 从“看懂接口 + 查到文档知识”升级为“能按任务阶段组装可追踪上下文”。

## Solution

实现 Phase 4 的 Memory 写路径、Memory 读路径和 Unified Context Builder：

```text
Task Step / Observation / User Feedback / Execution Summary
-> TaskMemoryService.writeTaskMemory(...)
-> MemoryCandidate
-> MemoryRefineryService
-> LongTermMemory

ApiSpec / Task / Session / Stage / Query
-> UnifiedContextBuilder
-> Task State
-> Session Memory
-> Task Memory
-> KnowledgeRetrievalApplicationService
-> LongTermMemoryRetriever
-> ContextBundle
```

Phase 4 的目标是建立稳定上下文基础，而不是开始生成测试用例或执行 HTTP 请求。

Memory 分三层：

- `Session Memory`：当前会话短期上下文，保存最近目标、最近决策、最近失败原因和重试状态。优先复用现有 Redis 模型；如果本地测试无法依赖 Redis，可提供 test profile fallback，但生产模型仍保持 Session Memory 概念。
- `Task Memory`：当前 Task 的持续事实，按 taskId 精确读取，保存接口摘要、已完成步骤、执行摘要、失败样本、分析结论和下一步建议。
- `Long-term Memory`：跨任务可复用经验，来自 Memory Refinery 提纯后写入，支持结构过滤、标签召回、embedding 相似度、importance、confidence、hitCount、successContribution 和 freshness 排序。

Memory Refinery 是异步后台概念，但 Phase 4 可以先实现可测试的同步应用服务 seam。它接收 MemoryCandidate，做确定性的 value judgment、classification、deduplication、merge、compression、tagging 和 fake embedding，写入 LongTermMemory。后续真实异步 scheduler、LLM 提纯和节流合并可以在更后阶段增强。

Unified Context Builder 是 Phase 4 的读路径核心。它根据当前 taskId、sessionId、ApiSpec、阶段 profile、query 和 token budget 组装 `ContextBundle`。它必须保持来源分区和引用：

- `taskGoal`
- `apiContext`
- `taskState`
- `sessionContext`
- `taskMemory`
- `knowledgeContext`
- `longTermMemoryContext`
- `constraints`
- `citations`
- `conflicts`
- `budget`

合并规则：

- 当前 Task Memory 优先于 Long-term Memory。
- Knowledge RAG 表示稳定文档知识，Memory 表示运行经验。
- 官方高权威 Knowledge 优先于低置信 Memory。
- 高置信、高贡献 Memory 可以作为风险提示保留。
- RAG 和 Memory 冲突时，不静默覆盖，而是在 ContextBundle 中标记 conflict。
- token budget 优先保留当前任务事实，再保留高权威 RAG，最后保留高贡献 Memory。

推荐的测试 seams 是三个最高层服务：

- `TaskMemoryService`：给定 taskId 和任务事实，断言 Task Memory 写入、读取、状态过滤、过期过滤和幂等行为。
- `MemoryRefineryService`：给定 MemoryCandidate，断言价值判断、分类、去重/合并、LongTermMemory 写入和 fake embedding。
- `UnifiedContextBuilder`：给定 taskId、sessionId、ApiSpec、stage profile 和 token budget，断言 ContextBundle 的来源分区、排序、引用、冲突标记和降级行为。

## User Stories

1. As a developer, I want to write Task Memory for a task, so that later steps can recover what already happened.
2. As a developer, I want Task Memory to be queried by taskId, so that the current task can load all relevant facts deterministically.
3. As a developer, I want Task Memory to record lifecycle stage, so that context can distinguish analysis, generation, execution, failure analysis, and reporting facts.
4. As a developer, I want Task Memory to preserve source type and source reference, so that every fact can be traced back to a task state, observation, execution result, or user feedback.
5. As a developer, I want Task Memory to store summary and full content separately, so that context builders can use short text while preserving detailed evidence.
6. As a developer, I want Task Memory to store tags and metadata, so that downstream context can group task facts by API, stage, error code, and risk.
7. As a developer, I want expired or inactive Task Memory excluded from default context, so that stale task facts do not pollute later steps.
8. As a developer, I want duplicate Task Memory writes to be controlled, so that repeated agent steps do not create noisy duplicates.
9. As a developer, I want Session Memory to store recent session decisions, so that short-term context survives across nearby turns.
10. As a developer, I want Session Memory to have TTL behavior, so that temporary context expires automatically.
11. As a developer, I want Session Memory retrieval by sessionId, so that Unified Context Builder can include recent decisions.
12. As a developer, I want Session Memory to avoid raw transcript dumps, so that context remains compact and useful.
13. As a developer, I want to submit MemoryCandidate records, so that potentially reusable experience can be evaluated before becoming long-term memory.
14. As a developer, I want MemoryCandidate to capture source facts from Task Memory, Observation, execution summaries, and user feedback, so that experience can be traced to concrete evidence.
15. As a developer, I want Memory Refinery to reject one-off noise, so that Long-term Memory does not become a junk drawer.
16. As a developer, I want Memory Refinery to classify reusable experience, so that later retrieval can distinguish project knowledge, testing pattern, failure pattern, and preference.
17. As a developer, I want Memory Refinery to compress raw facts into reusable summaries, so that long-term memory is concise.
18. As a developer, I want Memory Refinery to preserve fullContent, so that a human can inspect the source evidence later.
19. As a developer, I want Memory Refinery to tag memories, so that retrieval can use module, API path, error code, auth, payment, order, risk, and preference tags.
20. As a developer, I want Memory Refinery to assign confidence and importance, so that weak memories can rank lower.
21. As a developer, I want Memory Refinery to generate embeddings through the existing embedding boundary, so that long-term memory can support semantic recall without external services in tests.
22. As a developer, I want Memory Refinery to deduplicate similar candidates, so that repeated failures update an existing memory instead of creating copies.
23. As a developer, I want Memory Refinery to merge stronger evidence into existing memories, so that hit count, confidence, contribution, and version-like metadata can evolve.
24. As a developer, I want Long-term Memory to be queried by structured scope, so that unrelated project experiences do not pollute context.
25. As a developer, I want Long-term Memory to be queried by tags, so that exact concepts like 401, auth, payment, tenant, signature, and timeout can be recalled.
26. As a developer, I want Long-term Memory to be queried by semantic similarity, so that similar failures and testing strategies can be found even with different wording.
27. As a developer, I want Long-term Memory ranking to consider structure match, tag match, vector similarity, importance, confidence, success contribution, hit count, and freshness, so that useful memories appear first.
28. As a developer, I want stage-specific memory profiles, so that case generation, execution preparation, failure analysis, and reporting retrieve different memory types.
29. As a developer, I want memory retrieval to update hitCount and lastUsedAt when a memory is used, so that future ranking can learn from usage.
30. As a developer, I want archived and inactive memories excluded by default, so that deprecated experience does not influence new tasks.
31. As a developer, I want Unified Context Builder to accept taskId, sessionId, ApiSpec, stage profile, raw query, and token budget, so that every downstream module has one context entrypoint.
32. As a developer, I want Unified Context Builder to include ApiSpec and code-derived context, so that downstream modules know the interface structure.
33. As a developer, I want Unified Context Builder to include KnowledgeContext from Phase 3, so that document knowledge is available beside memory.
34. As a developer, I want Unified Context Builder to include Task Memory, so that current task facts are not lost.
35. As a developer, I want Unified Context Builder to include Session Memory, so that recent user and agent decisions are preserved.
36. As a developer, I want Unified Context Builder to include Long-term Memory, so that historical failure patterns and testing preferences can influence later work.
37. As a developer, I want ContextBundle to keep RAG knowledge and Memory experience in separate sections, so that downstream prompts can distinguish source types.
38. As a developer, I want ContextBundle citations for knowledge chunks and memory items, so that downstream output can be traced.
39. As a developer, I want ContextBundle to mark conflicts between RAG and Memory, so that planners do not silently trust contradictory context.
40. As a developer, I want ContextBundle to apply token budget rules, so that prompts do not become bloated.
41. As a developer, I want task facts to win over stale long-term memories, so that this run's real execution is respected.
42. As a developer, I want official high-authority documents to win over low-confidence memories, so that documented rules remain authoritative.
43. As a developer, I want high-confidence historical failure patterns to be preserved as risk hints, so that known pitfalls are not ignored.
44. As a developer, I want empty Memory stores to degrade gracefully, so that Knowledge RAG and ApiSpec context can still be used.
45. As a developer, I want empty Knowledge RAG to degrade gracefully, so that Memory and ApiSpec context can still be used.
46. As a developer, I want ContextBundle to report coverage and confidence, so that downstream modules know whether context is strong or weak.
47. As a developer, I want ContextBundle to be deterministic in tests, so that agent-driven implementation can be verified reliably.
48. As a developer, I want no TestCase generation in Phase 4, so that context quality is validated before generation begins.
49. As a developer, I want no HTTP execution in Phase 4, so that Memory and Context remain independent of execution engine implementation.
50. As a developer, I want no report rendering in Phase 4, so that reporting remains a later phase.
51. As a developer, I want no frontend UI in Phase 4, so that backend context behavior lands first.
52. As a developer, I want no real external embedding provider required in Phase 4, so that tests remain stable.
53. As a developer, I want Memory Refinery to be callable synchronously in tests, so that its behavior can be verified without background timing flakiness.
54. As a developer, I want the future async scheduler boundary to be explicit, so that later phases can make refinement asynchronous without changing core logic.
55. As a maintainer, I want Phase 4 acceptance tests, so that Memory and Unified Context behavior stays inside V1 HTTP API testing boundaries.
56. As a future agent, I want a clear Phase 4 PRD and issue set, so that implementation can proceed one vertical slice at a time.

## Implementation Decisions

- Build Phase 4 on top of the existing Memory persistence models, Phase 2 ApiSpec model, and Phase 3 Knowledge RAG services.
- Do not create a new backend project.
- Do not replace the existing TaskMemoryItem, SessionMemoryItem, LongTermMemory, repository, vector converter, or KnowledgeContext foundations unless a minimal schema gap is discovered.
- Add a Task Memory application service for writing and reading task-scoped facts.
- Add a Session Memory application service for writing and reading session-scoped short-term context.
- Add a MemoryCandidate model or equivalent request object that can represent candidate experience from Task Memory, Observation, execution summary, user feedback, or manual input.
- Add a Memory Refinery application service as the long-term memory write boundary.
- Keep Memory Refinery core logic synchronous and deterministic in Phase 4 for testability.
- Represent future async behavior as an explicit scheduler/enqueue boundary, but do not require real background workers in Phase 4.
- Memory Refinery should perform deterministic value judgment before writing Long-term Memory.
- Value judgment should reject empty, one-off, low-confidence, task-local-only, and non-reusable candidates.
- Memory Refinery should classify candidates into existing memory scope types where possible.
- If existing scope types are insufficient, add only minimal enum values needed for the PRD vocabulary.
- Memory Refinery should deduplicate by scope, source scope, normalized summary/content, tags, and metadata hints.
- When deduplicating, prefer merging evidence into an existing active LongTermMemory over creating duplicates.
- LongTermMemory updates should preserve evidence through fullContent, metadata, source refs, hit count, confidence, importance, success contribution, and timestamps where the model supports it.
- Embedding for LongTermMemory should reuse the Phase 3 embedding boundary or a clearly shared embedding abstraction.
- Long-term memory retrieval should use structured filtering, tag matching, fake embedding similarity, ranking, and active-status filtering.
- Ranking should be centralized and stage-aware.
- Stage profiles should include at least case generation, execution preparation, failure analysis, and report generation.
- Retrieval should update hitCount and lastUsedAt when memories are selected for context.
- Add a Unified Context Builder application service as the main read boundary for downstream phases.
- Unified Context Builder should accept taskId, sessionId, ApiSpec or apiSpecId, stage profile, raw query, structured filters, and token budget.
- Unified Context Builder should call Phase 3 KnowledgeRetrievalApplicationService rather than duplicating RAG retrieval logic.
- Unified Context Builder should call LongTermMemory retrieval rather than reading all memories blindly.
- Unified Context Builder should read Task Memory by taskId and Session Memory by sessionId.
- ContextBundle should be a structured result object, not a single concatenated prompt string.
- ContextBundle should preserve section boundaries for apiContext, taskState, sessionContext, taskMemory, knowledgeContext, longTermMemoryContext, constraints, citations, conflicts, and budget.
- Citations should include source type, source id, source ref, confidence, and score where available.
- Conflict detection should start with deterministic rules, such as same tag/API path/error code with contradictory status, requirement, or environment hints.
- Token budgeting should be deterministic and conservative, using estimated token counts if no tokenizer exists.
- Token budget priority should be task facts, then ApiSpec/code context, then high-authority Knowledge, then high-confidence/high-contribution Memory.
- Empty stores should degrade gracefully and produce explicit coverage information.
- Keep RAG feedback weight adjustment out of Phase 4 unless only preserving integration metadata.
- Keep TestCase generation, TestCaseDraft promotion, HTTP execution, assertion evaluation, report rendering, and frontend UI out of Phase 4.
- Maintain the V1 boundary: HTTP API testing only. Do not introduce UI automation, browser automation, service direct invocation, or DB direct assertions.
- Maintain the existing Java package style, Spring service style, repository style, Flyway migration style, and integration test style.

## Testing Decisions

- Tests should verify external behavior through application service seams, not private helper methods.
- The highest Task Memory seam should be TaskMemoryService or equivalent.
- The highest Memory Refinery seam should be MemoryRefineryService or equivalent.
- The highest context seam should be UnifiedContextBuilder or equivalent.
- Add Task Memory tests for write, read by taskId, lifecycle stage filtering, active/inactive filtering, expiry filtering, metadata preservation, and duplicate handling.
- Add Session Memory tests for write, read by sessionId, TTL fields, compact context behavior, and active filtering.
- Add Memory Refinery tests for accepting useful candidates, rejecting noise, classifying scope type, tagging, confidence/importance assignment, embedding, and LongTermMemory persistence.
- Add Memory Refinery deduplication tests where a repeated candidate updates or merges with an existing active LongTermMemory.
- Add Long-term Memory retrieval tests for structure match, tag match, vector similarity, stage profile ranking, inactive/archive exclusion, hitCount updates, and lastUsedAt updates.
- Add Unified Context Builder tests with seeded ApiSpec, KnowledgeContext, Task Memory, Session Memory, and LongTermMemory.
- Unified Context Builder tests should assert sectioned output, citations, ordering, conflict detection, token budget truncation, and graceful degradation when one source is empty.
- Add acceptance boundary tests proving Phase 4 does not introduce TestCase generation, HTTP execution, report rendering, frontend UI, browser automation, service direct invocation, or DB direct assertions.
- Reuse existing Phase 1/2/3 testing patterns: Spring Boot integration tests, repository tests, fixture-based application service tests, and boundary guard tests.
- Do not test LLM-based memory extraction, real async scheduling, external embedding providers, production Redis connectivity beyond repository behavior, TestCase generation, HTTP execution, assertion evaluation, report rendering, or UI behavior in Phase 4.

## Out of Scope

- TestCase generation.
- TestCaseDraft generation.
- Draft review, promotion, and stale detection.
- SINGLE, SUITE, or BATCH generation pipelines.
- HTTP execution engine.
- Assertion suggestion or assertion evaluation.
- Failure analysis engine beyond memory/context inputs.
- Report rendering.
- RAG feedback weight adjustment.
- Real LLM-based Memory Refinery extraction.
- Real async background scheduler, distributed worker, queue, or retry infrastructure.
- Production BGE-M3 deployment.
- Production OpenAI embedding integration.
- New vector database infrastructure beyond existing storage.
- Elasticsearch, Milvus, Neo4j, Kafka, or new distributed infrastructure.
- Visual memory editor.
- Frontend UI.
- UI automation.
- Browser automation.
- Service direct invocation.
- DB direct assertions.
- Non-HTTP API testing.
- Team Knowledge Memory as a separate human-maintained memory product.

## Further Notes

- Phase 4 should make ProbeFlow able to say: “I can assemble the right context for the current task stage from ApiSpec, Knowledge RAG, Task Memory, Session Memory, and Long-term Memory.”
- Phase 4 is the prerequisite for strong test case generation. Without Unified Context Builder, later generators will either miss important context or overstuff prompts with unranked history.
- RAG is not Memory. RAG stores stable human-written document knowledge. Memory stores runtime experience learned by the Agent.
- Memory Refinery is the only long-term memory write path. Raw execution logs, full conversations, and temporary reasoning should not be written directly into LongTermMemory.
- In Phase 4, Memory Refinery can be deterministic and lightweight. The important thing is to land the seam and lifecycle before adding LLM-powered refinement.
- ContextBundle must be traceable. Every included knowledge item and memory item should carry source identifiers and confidence/score metadata.
- ContextBundle must be compact. The goal is not to include everything, but to include the most useful, stage-appropriate, budget-aware context.
- Keep Phase 4 backend-only and deterministic so that AFK agents can implement it issue by issue with reliable tests.
