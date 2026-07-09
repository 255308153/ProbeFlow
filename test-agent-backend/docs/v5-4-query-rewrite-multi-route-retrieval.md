# ProbeFlow V5-4 Query Rewrite 与 Multi-route Retrieval

V5-4 的目标是把 ProbeFlow 的读路径从“一个原始 query + 一次召回”推进到“结构化 query variant + Knowledge / Memory / Graph 多路召回 + 可解释 route fusion + Unified Context evidence”。

它解决的是召回覆盖和召回解释问题：同一个接口测试任务可以同时寻找接口说明、业务流程、错误码文档、测试规范、历史失败经验、SUITE 变量经验和 Memory Graph 结构关系。V5-4 不追求最终排序最优，不实现 Cross Encoder Rerank、LLM Rerank、Small-to-Big 或父子索引；这些属于 V5-5 的精排和上下文扩展边界。

核心链路：

```text
Task / ApiSpec / Failure / Suite Context
-> Deterministic Query Rewrite
-> QueryVariant
-> StageRoutingProfile
-> Knowledge routes + Memory routes + Graph route
-> Route Fusion / Dedupe / Budget
-> route evidence + low confidence diagnostics
-> Unified Context citation evidence
```

## Query Variant

默认 Query Rewrite 由 `DeterministicQueryRewriteService` 完成。它不调用真实 LLM，不访问外部网络，也不需要 DeepSeek key。相同输入会生成稳定的 `deterministicId`，便于 CI、回归测试和 citation 追踪。

每个 `QueryVariant` 代表一次可解释的检索意图，包含：

- `deterministicId`：稳定 query variant id。
- `queryText`：实际用于检索的文本。
- `intent`：例如 raw task、api structure、business rule、test strategy、failure reason、error code、suite variable、policy learning。
- `targetCorpus`：knowledge、memory、graph 或 all。
- `stageProfile`：当前任务阶段。
- `filters`：system、module、apiPath、httpMethod、businessEntity、errorCode、suiteId、variableKey、policyReason、toolName、tags、documentTypes、factTypes。
- `priority` 与 `reason`：解释为什么生成这条 variant。

LLM-assisted rewrite 只保留为显式手动 profile 或内部试用路径，不能进入默认测试路径。内部试用需要真实 LLM 验收时，可以使用 DeepSeek V4 Pro 手动验证 rewrite 或 Agent 决策链路，但 fake LLM 不能作为 internal alpha 唯一验收；默认单测和 CI 仍必须不依赖真实 LLM、真实外部网络或真实 DeepSeek key。

## Retrieval Route

V5-4 的 route 是“召回路线”，不是最终精排器。

Knowledge routes 覆盖：

- `original-semantic`：保留原始 query 的语义召回，作为稳定 fallback。
- `rewritten-semantic`：使用 query variant 做语义召回，覆盖字面不同但语义相关的文档。
- `metadata-exact`：使用 apiPath、httpMethod、system、module、bizEntity、docType、applicableStage 等结构字段。
- `lexical-tag`：使用错误码、字段名、标题、标签和 frontmatter 词。
- `document-type`：按 API note、business flow、test spec、domain rule、error code guide、incident postmortem 等文档类型补齐候选。

Memory routes 覆盖：

- `semantic_memory`：用原始 query 或改写 query 召回长期记忆。
- `metadata_memory`：按 scopeType、factType、apiPath、errorCode、businessEntity、tags、suiteId、variableKey、policyReason 等结构字段召回。
- `graph_memory`：复用 V5-3 Memory Entity Graph 的关系扩展结果。
- `exact_entity`：对 errorCode、apiPath、variableKey、toolName、policyReason 等高精度实体做稳定命中。

## V5-3 与 V5-4 的关系

V5-3 的 Memory Graph 是 Long-term Memory 的 projection，用来表达 memory fact 之间的结构关系。V5-4 不把 Memory Graph 变成新的长期记忆 source of truth，也不让它替代 Knowledge RAG 或普通 Long-term Memory retrieval。

在 V5-4 中，Memory Graph 只是 `graph_memory` 这一条 graph route 的来源之一：

- Knowledge retrieval 仍负责人类写好的文档知识。
- Long-term Memory retrieval 仍负责 Agent 运行后沉淀的经验。
- Memory Graph route 只补充结构关系，例如 errorCode、businessEntity、variableKey、toolName、policyReason 的相邻经验。
- Memory Refinery 仍然是长期记忆写入入口；V5-4 只改变读路径和 Unified Context evidence。

## V5-4 与 V5-5 的边界

V5-4 的关键词是 recall coverage 和 route explanation：

- 扩大候选覆盖面。
- 合并多路召回候选。
- 保留 query variant、route rank、route score、match reason 和 fallback diagnostics。
- 将 route evidence 透传给 Unified Context。
- 在预算不足、候选为空或 route 失败时给出 low confidence 说明。

V5-5 的关键词是 rerank 和 Small-to-Big：

- 对 V5-4 候选池做 deterministic rerank 或后续精排。
- 对小 chunk / memory evidence 扩展到更大的父级上下文。
- 比较 rerank 前后排序变化。
- 继续保留并消费 V5-4 的 route evidence，而不是替代它。

因此，V5-4 不实现 Cross Encoder Rerank、LLM Rerank、Small-to-Big 或父子索引。即使仓库后续存在 V5-5 的 `rerank` 包，V5-4 的 `retrieval`、`knowledge`、`memory` 读路径也不应依赖这些精排实现。

## Stage Profile

`StageRoutingProfileCatalog` 按任务阶段启用不同 route、权重、文档类型、记忆 fact 类型、每路 limit、fallback 和预算策略。

典型阶段：

- API analysis：优先 metadata exact、原始语义、改写语义、document type 和 lexical/tag，偏向 API note、business flow、domain rule。
- Case generation：优先 rewritten semantic、document type、metadata exact、semantic memory 和 lexical/tag，偏向 test spec、domain rule、testing pattern。
- Failure analysis：优先 exact entity、metadata exact、document type、semantic memory、metadata memory、graph memory 和 rewritten semantic，偏向 error code guide、incident postmortem、failure pattern。
- Suite generation / suite recovery：优先 metadata exact、exact entity、document type、metadata memory、graph memory 和 rewritten semantic，偏向 business flow、suite dependency fact、variable extraction fact。
- Planner / policy：优先 exact entity、metadata memory、semantic memory 和 rewritten semantic，偏向 policy learning、toolName、policyReason。
- Report：优先 confirmed observation、failure analysis、高置信 memory 和关键 citation。

## Route Fusion、Budget 与 Fallback

V5-4 的 fusion 是轻量、可解释的候选合并，不是 V5-5 精排。它需要满足：

- 同一个 KnowledgeChunk 或 LongTermMemory 只出现一次。
- 同一候选命中多条 route 时合并 route evidence。
- 保留每条 route 的 rank、score、query variant、intent、match reason 和 source evidence。
- 输出 `fusedScore` 与 `fusionExplanation`，说明 route weight、rank contribution 和 route agreement。
- 遵守 per-route limit、总候选上限、token budget 和低置信过滤。

预算治理由 `RoutingBudgetPolicy`、Knowledge retrieval、Long-term Memory retrieval 和 Unified Context Builder 共同执行。候选爆炸时，V5-4 应限制每路候选、限制最终上下文 token，并在全部候选被预算剪掉时产生 low confidence diagnostic。某条 route 失败时，诊断必须脱敏，并 fallback 到原始语义召回或其他可用 route。

## Unified Context Evidence

Unified Context Builder 不需要理解每条 route 的内部实现，但必须把 V5-4 evidence 透传给 citation。Knowledge 和 Memory citation 的 evidence 应包含：

- `routeEvidence`
- `retrievalRoutes` 或 `routeNames`
- `queryVariantIds`
- `queryVariantIntents`
- `queryVariantIntentById`
- `preFusionRanks`
- `fusedScore`
- `fusionExplanation`
- `lowConfidence` 与 `lowConfidenceReason`
- graph route 的 `graphRelationPath`、`graphRelationConfidence`、`graphSourceMemoryIds`、`graphSourceRefs` 等 source evidence

这样 Planner、用例生成、失败分析、报告生成和人工验收可以回答“为什么这条材料进入上下文”。

## 默认测试与内部试用边界

默认测试边界：

```bash
cd test-agent-backend
mvn test
```

默认测试必须使用 deterministic query rewrite、fake LLM、fake / local seam、H2 测试库和可控 fixture。它不得要求真实 DeepSeek key、真实 LLM、外部网络、Cross Encoder 服务或外部图数据库。

内部试用边界：

- 真实 LLM 手动验收路径必须保留，DeepSeek V4 Pro 可以用于人工验证 rewrite、Planner 或 Agent 决策链路。
- 真实 LLM 必须显式开启，且受 provider policy、allow flag、key 配置和成本边界约束。
- fake LLM 可以作为 deterministic baseline，但不能作为 internal alpha 唯一验收。

## 外部系统边界

V5-4 不接入 mem0 SDK、VikingDB、Neo4j 或新的外部图数据库。ProbeFlow 可以借鉴 memory extraction、entity graph、route fusion 和检索评估思想，但核心能力仍放在自研的 RAG / Memory / Context Engine 内。

这条边界让 ProbeFlow 的设计可以清楚拆分：

- Knowledge RAG 管文档知识。
- Memory Refinery 管长期记忆写入。
- Long-term Memory retrieval 管经验读取。
- Memory Graph 管 memory projection 和 graph route。
- Query Rewrite / Multi-route Retrieval 管召回覆盖和解释。
- V5-5 rerank / Small-to-Big 管后续精排和上下文扩展。
