状态：ready-for-agent

# ProbeFlow V5-1：真实 Embedding 与 pgvector 检索 PRD

## Problem Statement

ProbeFlow 已经完成 V4 的真实 LLM 手动实验与 Demo Console。用户现在可以在本地页面里展示 Agent 如何规划、调用工具、构建上下文、执行 SUITE、分析失败、沉淀 Memory Feedback，并对比 fake 与 real LLM 的行为差异。

但 V4 之前的 RAG / Memory 召回仍然主要停留在“可测的基础版”：

- `EmbeddingService` 已经存在，但默认是 deterministic fake embedding。
- `KnowledgeChunk` 与 `LongTermMemory` 已经有 `vector(1024)` 字段和 HNSW index，但检索路径仍以应用内候选过滤和 Java 层相似度排序为主。
- RAG 可以导入文档、切 chunk、补 metadata、做基础 hybrid ranking，但还没有真正使用 pgvector 的近邻检索能力。
- Memory 可以沉淀长期经验并参与 Unified Context，但长期记忆召回还没有成为真实向量检索路径。
- V5 后续要做 Query Rewrite、多路召回、rerank、Small-to-Big 和自研 Memory Engine，如果没有真实 embedding 与 pgvector 检索地基，后续能力会缺少可信支撑。

用户在面试中最想讲的是 Agent 设计。V5-1 要补上的不是“接个模型 API”这么薄的一层，而是让 ProbeFlow 能讲清楚：

```text
文档知识与运行经验
-> 真实语义向量化
-> pgvector 持久化索引
-> 阶段化语义召回
-> metadata filter / token budget / citation
-> Unified Context 消费
```

V5-1 完成后，ProbeFlow 的 RAG / Memory 不再只是 fake scoring。用户可以解释为什么某条业务文档或某条历史失败经验被召回，向量召回如何与结构化过滤协同，以及为什么默认测试仍然稳定可控。

## Solution

新增 V5-1 真实 Embedding 与 pgvector 检索基础能力。

V5-1 的核心链路是：

```text
Knowledge Ingest / Memory Refinery
-> EmbeddingService
-> FakeEmbeddingProvider 或 RealEmbeddingProvider
-> vector(1024) persistence
-> Pgvector nearest-neighbor retrieval
-> KnowledgeRetrievalApplicationService / LongTermMemoryRetrievalService
-> Unified Context Builder
```

V5-1 分成五个能力面。

第一，真实 embedding provider。

- 保留 fake embedding 作为默认 provider，保证本地开发、CI、单元测试稳定。
- 新增真实 embedding profile，必须显式开启。
- 真实 provider 继续挂在现有 `EmbeddingService` seam 后面。
- 真实 provider 支持配置 endpoint、model、dimension、timeout、max input tokens、query instruction prefix、document instruction prefix、batch size 和 failure policy。
- 查询向量和文档向量必须区分处理：有些中文 embedding 模型查询侧需要 instruction prefix，入库侧不需要或使用不同 prefix。
- provider 返回维度必须与系统配置和数据库 `vector(n)` 维度一致，不一致时写入或查询要清晰失败。
- 默认 `mvn test` 和默认 CI 不调用真实 embedding 服务。

第二，pgvector 检索路径。

- `KnowledgeRetrievalApplicationService` 使用 pgvector 进行候选召回，而不是把所有 active chunk 拉到应用内做完整排序。
- `LongTermMemoryRetrievalService` 使用 pgvector 进行长期记忆候选召回，而不是全量扫描 active memory。
- pgvector 召回应和结构化条件协同：systemName、moduleName、bizEntity、apiPath、httpMethod、documentType、applicableStage、tags、scopeType、errorCode 等仍然参与过滤或排序。
- 检索结果继续返回可解释的 component scores 和 match reasons，不能变成一个不可解释的黑盒向量分。
- HNSW index 是性能基础，但 V5-1 不追求生产级压测，只要保证架构路径正确、查询可验证、后续可扩展。

第三，RAG 与 Memory 统一 embedding 配置。

- Knowledge Chunk 和 Long-term Memory 使用同一套 embedding provider contract。
- 不强制它们永远使用同一个模型，但 V5-1 默认使用同一个 dimension/profile，避免向量维度和召回语义不一致。
- 文档写入、Memory Refinery 写入、query retrieval 都必须记录 provider profile 信息，方便后续排查“这条向量来自哪个模型/配置”。
- 当 provider profile 变化时，系统必须能识别旧向量与当前 profile 不一致，并给出 reindex 需要，而不是默默混用。

第四，可控失败与降级。

- 真实 embedding 服务不可用、超时、返回空向量、返回错误维度、返回非数字向量时，系统要给出清晰错误。
- fake provider 仍然可用于无外部依赖的验收测试。
- 在真实 provider 不可用时，默认路径不应自动悄悄切到 fake provider，除非 profile 明确声明允许 fallback；否则用户会误以为真实语义召回已经生效。
- 错误信息不能泄漏 embedding endpoint token、API key、Authorization header 等敏感信息。

第五，为后续 V5-4 / V5-5 留扩展点。

- V5-1 只打通真实 embedding 与 pgvector 召回，不做完整 Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank 或 Small-to-Big。
- 检索结果需要保留 future fields 或 metadata 扩展点，例如 retrievalChannel、embeddingProfile、vectorDistance、lexicalScore、metadataScore、candidateRank、parentChunkId、citation、lowConfidenceReason。
- 后续 V5-4 可以在这个基础上加入 query rewrite 和多路召回。
- 后续 V5-5 可以在这个基础上加入 rerank 和父子索引。

最高测试 seam 采用两个应用级读取 seam：

- `KnowledgeRetrievalApplicationService`：验证文档 RAG 的真实 embedding / pgvector 召回行为。
- `LongTermMemoryRetrievalService`：验证长期 Memory 的真实 embedding / pgvector 召回行为。

真实 embedding provider 自身只做 contract/fake client 测试，不把外部模型调用放进默认测试。

## User Stories

1. As an API 测试 Agent 用户, I want 文档知识使用真实 embedding 入库, so that RAG 召回不再只是 fake scoring。
2. As an API 测试 Agent 用户, I want 长期记忆使用真实 embedding 入库, so that Memory 能按语义召回历史经验。
3. As an API 测试 Agent 用户, I want 默认仍使用 fake embedding, so that 我不配置外部服务也能运行测试。
4. As an API 测试 Agent 用户, I want 显式开启 real embedding profile, so that 我能手动验证真实语义召回效果。
5. As an API 测试 Agent 用户, I want real embedding 配置缺失时得到清晰错误, so that 我知道是 endpoint、model、key、dimension 还是 timeout 配置问题。
6. As an API 测试 Agent 用户, I want embedding 错误不泄漏 token, so that 本地演示和日志不会暴露敏感信息。
7. As an API 测试 Agent 用户, I want 查询侧能配置 instruction prefix, so that BGE 类模型的检索效果不会因为缺少查询前缀而下降。
8. As an API 测试 Agent 用户, I want 文档侧能独立配置 prefix, so that 入库向量不会被错误查询指令污染。
9. As an API 测试 Agent 用户, I want 系统校验 embedding 维度, so that 1024 维索引不会写入错误维度向量。
10. As an API 测试 Agent 用户, I want provider profile 记录在检索结果或 metadata 中, so that 我知道某条向量由哪个模型生成。
11. As an API 测试 Agent 用户, I want provider profile 变化时系统能提示 reindex, so that 新旧模型向量不会混用。
12. As an API 测试 Agent 用户, I want Knowledge Retrieval 使用 pgvector 召回候选, so that 文档量增加后不需要全量扫描。
13. As an API 测试 Agent 用户, I want Long-term Memory Retrieval 使用 pgvector 召回候选, so that 长期经验变多后仍能按语义找回。
14. As an API 测试 Agent 用户, I want metadata filter 与向量召回一起工作, so that 搜索结果仍限定在正确系统、模块、接口或业务实体内。
15. As an API 测试 Agent 用户, I want apiPath 和 httpMethod 仍参与过滤, so that `/orders/{id}/pay` 的知识不会误召回到无关接口。
16. As an API 测试 Agent 用户, I want documentType 仍参与过滤, so that 生成用例时优先召回测试规范，失败分析时优先召回错误码或复盘。
17. As an API 测试 Agent 用户, I want applicableStage 仍参与过滤或排序, so that 不同 Agent 阶段拿到不同上下文。
18. As an API 测试 Agent 用户, I want tags 仍参与过滤或排序, so that 领域标签能约束召回范围。
19. As an API 测试 Agent 用户, I want memory scopeType 参与过滤, so that failure_pattern、testing_pattern、preference 不会混在一起。
20. As an API 测试 Agent 用户, I want errorCode 参与 Memory 检索, so that 相同错误码的历史经验能被优先召回。
21. As an API 测试 Agent 用户, I want 检索结果保留 vectorDistance, so that 我能解释向量相似度贡献。
22. As an API 测试 Agent 用户, I want 检索结果保留 metadataScore, so that 我能解释结构化匹配贡献。
23. As an API 测试 Agent 用户, I want 检索结果保留 lexicalScore 或 keyword evidence, so that 纯关键词命中仍可解释。
24. As an API 测试 Agent 用户, I want 检索结果保留 final score, so that Agent 能按统一排序消费上下文。
25. As an API 测试 Agent 用户, I want 检索结果保留 match reasons, so that 面试展示时能讲清为什么召回这条证据。
26. As an API 测试 Agent 用户, I want 检索结果保留 lowConfidence 标记, so that Agent 知道上下文不足时需要谨慎。
27. As an API 测试 Agent 用户, I want token budget 仍生效, so that 召回内容不会挤爆 LLM 上下文。
28. As an API 测试 Agent 用户, I want limit 仍生效, so that 每次召回结果数量可控。
29. As an API 测试 Agent 用户, I want citation 仍保留 documentRevisionId 和 sourceRef, so that RAG 证据可以追溯。
30. As an API 测试 Agent 用户, I want Memory hit 仍更新 hitCount 和 lastUsedAt, so that 后续可以根据使用情况调权。
31. As an API 测试 Agent 用户, I want fake provider 和 real provider 共用同一接口, so that 代码中不会出现两套 embedding 逻辑。
32. As an API 测试 Agent 用户, I want 默认 CI 不访问真实 embedding 服务, so that 测试稳定且没有外部成本。
33. As an API 测试 Agent 用户, I want provider contract 测试使用 fake client, so that 可以验证请求构造、响应解析和错误分类。
34. As an API 测试 Agent 用户, I want ingestion 失败时能指出是 embedding 失败, so that 我不会误以为文档解析坏了。
35. As an API 测试 Agent 用户, I want retrieval 失败时能指出是 query embedding 失败, so that 我可以修 provider 配置。
36. As an API 测试 Agent 用户, I want 空文本不能被写入真实 embedding, so that 索引里不会出现无意义向量。
37. As an API 测试 Agent 用户, I want 超长文本在 embedding 前被明确处理, so that 外部模型不会因为 token 超限随机失败。
38. As an API 测试 Agent 用户, I want batch size 可配置, so that 大量 chunk 入库时可以控制吞吐和成本。
39. As an API 测试 Agent 用户, I want timeout 可配置, so that 外部 embedding 服务卡住时不会拖死任务。
40. As an API 测试 Agent 用户, I want retry/failure policy 可配置, so that 临时网络错误和永久配置错误能被区分。
41. As an API 测试 Agent 用户, I want pgvector 查询不会绕过文档状态, so that SUPERSEDED chunk 不会被召回。
42. As an API 测试 Agent 用户, I want pgvector 查询不会绕过 memory 状态, so that ARCHIVED 或 INACTIVE memory 不会被召回。
43. As an API 测试 Agent 用户, I want 新文档 revision 写入后只召回 latest active chunks, so that 旧版本知识不会污染结果。
44. As an API 测试 Agent 用户, I want 新 memory 合并后重新生成 embedding, so that 合并后的内容可以被正确召回。
45. As an API 测试 Agent 用户, I want 召回结果能进入 Unified Context, so that 后续 Agent 规划和失败分析能使用真实语义上下文。
46. As an API 测试 Agent 用户, I want Demo Console 后续能展示 embedding profile 和 semantic evidence, so that 我能把 V5 能力讲给面试官。
47. As an API 测试 Agent 用户, I want V5-1 不做 Query Rewrite, so that 本阶段范围聚焦，不和后续 V5-4 混在一起。
48. As an API 测试 Agent 用户, I want V5-1 不做 rerank 和 Small-to-Big, so that 本阶段先把向量召回地基做稳。
49. As an API 测试 Agent 用户, I want 系统不直接依赖 mem0 或 VikingDB, so that ProbeFlow 的 Memory / Context 能力是自研可讲解的。
50. As an API 测试 Agent 用户, I want README 说明 fake embedding 与 real embedding 的区别, so that 同事不会误解当前运行模式。
51. As an API 测试 Agent 用户, I want README 说明如何配置真实 embedding profile, so that 我能在本地手动测试。
52. As an API 测试 Agent 用户, I want README 说明 V5 后续路线, so that 团队知道 Query Rewrite、rerank、Small-to-Big 会在后续阶段完成。
53. As an API 测试 Agent 用户, I want 验收测试证明 pgvector schema 仍存在, so that 后续阶段不会丢掉向量索引基础。
54. As an API 测试 Agent 用户, I want 验收测试证明默认 provider 仍是 fake, so that 外部依赖不会意外进入 CI。
55. As an API 测试 Agent 用户, I want 手动 profile 可以证明 real embedding 请求会构造正确, so that 真实接入路径可信。
56. As an API 测试 Agent 用户, I want 检索排序同时考虑语义和结构, so that 只靠向量不会召回语义相似但业务错误的上下文。
57. As an API 测试 Agent 用户, I want 召回结果能暴露 retrievalChannel, so that 后续多路召回时可以知道来源通道。
58. As an API 测试 Agent 用户, I want 候选排序保留 candidateRank, so that 后续 rerank 可以解释排序前后变化。
59. As an API 测试 Agent 用户, I want 当前阶段能用测试数据稳定验证语义召回, so that 不依赖外部真实模型也能保护行为。
60. As an API 测试 Agent 用户, I want 当前阶段能手动跑真实 embedding, so that 我可以对比 fake 与真实语义向量效果。

## Implementation Decisions

- V5-1 保留现有 `EmbeddingService` 作为统一 seam，不让 Knowledge、Memory、Context 直接依赖某个第三方 SDK。
- Fake embedding 继续作为默认实现，用于本地默认启动、默认测试和 CI。
- 新增真实 embedding provider profile。真实 profile 必须显式开启，不允许默认自动访问外部服务。
- 真实 provider 配置至少包括 provider mode、endpoint、model、dimension、timeout、max input tokens、query prefix、document prefix、batch size、failure policy。
- V5-1 初始维度保持 1024，与现有 `vector(1024)` schema 对齐。不同维度模型不在本阶段自动混用；如需切换维度，应通过明确 migration / reindex 方案完成。
- `embedQuery` 与 `embedDocument` 保持语义区分。查询侧可以带 instruction prefix；文档侧默认不带查询 prefix。
- provider 返回维度必须被校验。写入 KnowledgeChunk、写入 LongTermMemory、查询 RAG、查询 Memory 时都不能接受错误维度。
- provider 错误需要分类，例如 missing config、timeout、remote error、invalid response、dimension mismatch、empty vector。
- provider 错误信息必须脱敏，不能暴露 API key、Authorization header、token 或完整敏感 endpoint 参数。
- Knowledge 写入链路继续由 Knowledge Ingest 负责：先文档治理、chunk、metadata，再调用 embedding。
- Memory 写入链路继续由 Memory Refinery 负责：先提纯、去重/合并、压缩、tag，再调用 embedding。
- Knowledge Retrieval 从“应用内全候选扫描 + Java cosine”升级为“结构化过滤 + pgvector 候选召回 + 可解释排序”。
- Long-term Memory Retrieval 从“active memory 全量扫描 + Java cosine”升级为“结构化过滤 + pgvector 候选召回 + 可解释排序”。
- 检索结果继续保留 component scores，不把所有排序原因隐藏成一个向量距离。
- vector distance 应作为 component score 参与 final score，同时与 keyword、metadata、authority、freshness、stageFit、importance、confidence、successContribution 等既有信号协同。
- `KnowledgeRetrievalApplicationService` 仍然负责输出 Knowledge Context、citation、coverage、lowConfidence、token budget 结果。
- `LongTermMemoryRetrievalService` 仍然负责输出 Long-term Memory hits、更新 hitCount / lastUsedAt、遵守 token budget。
- Unified Context Builder 不直接关心 provider 细节，只消费 RAG 和 Memory 的检索结果。
- 检索结果 metadata 应保留 embeddingProfile、retrievalChannel、vectorDistance、candidateRank 等后续 V5-4/V5-5 需要的信息。
- provider profile 变化后的旧向量不能静默混用。V5-1 至少要记录 profile 并能标记 profile mismatch / reindex required。
- 不直接引入 mem0 SDK 或 VikingDB。V5-1 是 ProbeFlow 自研 Memory / RAG / Context Engine 的向量地基。
- 不把真实 embedding 调用放进默认 CI。真实 provider 只通过 fake client、mock server 或手动 profile 验证。
- 文档需要说明 fake embedding 与 real embedding 的区别、真实 profile 配置方式、维度约束、默认测试边界和后续 V5 路线。

## Testing Decisions

- 测试只验证外部行为，不测试 private helper 或排序公式内部实现细节。
- V5-1 的最高测试 seam 是两个应用级读取 seam：`KnowledgeRetrievalApplicationService` 与 `LongTermMemoryRetrievalService`。
- `KnowledgeRetrievalApplicationService` 测试应覆盖：给定多份文档和真实/可控 embedding 向量，检索优先返回语义匹配且 metadata 正确的 active latest chunk。
- `KnowledgeRetrievalApplicationService` 测试应覆盖：documentType、stage、apiPath、httpMethod、tags 等结构化条件不会被 pgvector 召回绕过。
- `KnowledgeRetrievalApplicationService` 测试应覆盖：token budget、limit、citation、lowConfidence、component scores 和 match reasons 仍然存在。
- `LongTermMemoryRetrievalService` 测试应覆盖：长期记忆按语义召回，同时遵守 scopeType、apiPath、errorCode、status、token budget。
- `LongTermMemoryRetrievalService` 测试应覆盖：被选中的 memory 更新 hitCount 和 lastUsedAt。
- `EmbeddingService` contract 测试应覆盖 fake provider 和 real provider adapter 的共同语义：document/query 区分、dimension、empty input、错误分类。
- 真实 provider adapter 测试必须使用 fake client、mock server 或等价方式，不调用真实外部 embedding 服务。
- provider adapter 测试应覆盖请求构造、query/document prefix、timeout、remote error、invalid response、dimension mismatch、secret redaction。
- 持久层测试应覆盖 pgvector migration、`vector(1024)` 字段、HNSW index、active/latest/status filter。
- 验收边界测试应确认默认 provider 仍为 fake，默认 `mvn test` 不需要真实 embedding key。
- 验收边界测试应确认 V5-1 没有实现 Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank 或 Small-to-Big。
- Prior art 包括 Phase 3 的 Knowledge RAG 测试、Phase 4 的 Unified Context / Memory 测试、V4 的真实外部能力默认不进入 CI 的边界测试。
- 完整 `mvn test` 必须在无真实 embedding 配置、无外部 embedding 服务的环境下通过。
- 可选手动验证命令可以覆盖 real embedding profile，但不得成为默认测试套件的一部分。

## Out of Scope

- 不做 Query Rewrite。
- 不做多路召回编排。
- 不做 RRF。
- 不做 Cross-Encoder rerank。
- 不做 LLM rerank。
- 不做 Small-to-Big 父子索引。
- 不做 Memory fact extraction 完整版。
- 不做 Memory entity graph。
- 不做 Context Engine 全面重构。
- 不做 RAG 反馈调权仪表盘。
- 不做生产级 embedding 任务队列、重试队列或异步重建索引平台。
- 不做多模型混合向量空间。
- 不做向量维度在线迁移。
- 不接入 mem0 SDK。
- 不接入 VikingDB。
- 不让默认 CI 访问真实 embedding 服务。
- 不把真实 embedding key 写入仓库、测试代码或 demo artifact。
- 不改变 ProbeFlow 的 HTTP API 测试边界。

## Further Notes

V5-1 是 V5 系列的地基，不是完整 RAG Pro。它要解决的是“真实语义向量化和 pgvector 检索路径成立”。

V5-1 完成后，ProbeFlow 可以这样讲：

```text
1. 文档知识和历史经验都会进入统一 EmbeddingService。
2. 默认 fake provider 保证测试稳定，real provider 用显式 profile 手动开启。
3. KnowledgeChunk 和 LongTermMemory 都持久化到 pgvector。
4. RAG 和 Memory 召回不再全量扫描，而是走 pgvector 候选召回。
5. 向量距离不会变成黑盒，仍然和 metadata、stage、authority、confidence 等信号一起解释。
6. Unified Context Builder 消费的是带 citation、score、source、budget 的上下文结果。
7. 后续 V5-4 / V5-5 会继续补 Query Rewrite、多路召回、rerank 和 Small-to-Big。
```

V5 后续阶段建议保持如下顺序：

```text
V5-1: Real Embedding + pgvector Retrieval
V5-2: Memory Fact Extraction + Dedup
V5-3: Memory Entity Graph
V5-4: RAG Query Rewrite + Multi-route Retrieval
V5-5: Rerank + Small-to-Big
V5-6: Unified Context Engine Pro
```

这样 ProbeFlow 的 Agent 设计路线会很清楚：先把语义地基做实，再做记忆提纯，再做图谱关联，再做高级召回和上下文调度。
