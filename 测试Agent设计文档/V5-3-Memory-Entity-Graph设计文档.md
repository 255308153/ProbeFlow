# V5-3 Memory Entity Graph 设计文档

## 1. 阶段定位

V5-3 的目标是在 V5-2 已经提纯、去重、合并、治理过的长期记忆事实之上，投影出一层可查询、可追溯、可解释的 Memory Entity Graph。

它解决的问题不是“再存一份记忆”，而是让 Agent 能回答关系型问题：

- 某个错误码曾经出现在哪些 API 上。
- 某个 API 属于哪个 module / system。
- 某个 SUITE 变量由哪个 step 产出，又被哪些下游 step 消费。
- 某个业务实体需要哪些前置条件。
- 某个 policy reason 关联了哪些 tool。
- 两条文本不相似的长期记忆是否因为同一个 errorCode、apiPath、variableKey 或 businessEntity 相关。

Memory Graph 是 Long-term Memory 的 projection，不是新的长期记忆 source of truth。长期记忆写入仍然只有一个入口：Memory Refinery。图谱模块只读取 `ACTIVE` Long-term Memory、Memory Fact metadata、identity hints、tags、source refs 和 evidence ledger，再生成节点和边。

## 2. 主链路

V5-3 的完整链路如下：

```text
Memory Candidate
-> Memory Fact
-> Long-term Memory
-> Memory Entity Graph
-> Graph-expanded Unified Context
```

更细的实现链路如下：

```text
ACTIVE Long-term Memory / Memory Fact Metadata
-> Entity Extraction
-> Entity Normalization
-> Edge Projection
-> Provenance / Evidence Binding
-> Graph Rebuild / Upsert
-> Graph Query
-> Related Memory Expansion
-> Unified Context Citation Evidence
```

写路径仍然结束在 `MemoryRefineryService` 写入 `LongTermMemory`。Memory Graph 不接收 Memory Candidate，不创建 Memory Fact，不直接保存 `LongTermMemory`，也不绕过 Quality Gate、Dedup、Evidence Ledger 或 Identity Conflict Guard。

## 3. 节点类型

V5-3 覆盖的节点类型来自 ProbeFlow 的接口测试 Agent 领域模型：

- `memory`：长期记忆自身的图谱节点，用于从实体反查记忆。
- `system`：系统或服务边界。
- `module`：模块或业务组件。
- `apiPath`：HTTP API 路径。
- `httpMethod`：HTTP 方法。
- `errorCode`：业务错误码或网关错误码。
- `businessEntity`：订单、支付、库存、用户等业务对象。
- `failureClassification`：鉴权失败、变量缺失、前置条件失败、断言不匹配等失败分类。
- `suiteId` / `caseId`：链路套件和用例身份。
- `rootStepId` / `downstreamStepId`：SUITE 上游根因步骤和受影响下游步骤。
- `variableKey`：SUITE 变量名。
- `sourcePath`：变量提取的响应路径或字段路径。
- `toolName`：Planner、Policy Validator 或 Tool Contract 中的工具名。
- `policyReason`：策略拒绝、人工确认、高风险动作等原因码。
- `factType`：长期事实类型，例如 failure pattern、testing pattern、policy learning。
- `tag`：V5-2 质量门禁后的长期记忆标签。

节点身份使用 `entityType + normalizedValue + scope`。例如 `variableKey=orderId` 会按 suite scope 隔离，避免不同套件里的同名变量被错误合并。

## 4. 边类型

V5-3 的边表达可解释关系，而不是做通用图数据库产品：

- `MEMORY_MENTIONS_ENTITY` / `ENTITY_RELATED_TO_MEMORY`：长期记忆与实体的双向关联。
- `API_BELONGS_TO_MODULE` / `MODULE_BELONGS_TO_SYSTEM`：接口、模块、系统层级关系。
- `API_USES_HTTP_METHOD`：API 与 HTTP 方法关系。
- `ERROR_OBSERVED_ON_API`：错误码在某个 API 上被观察到。
- `FAILURE_CLASSIFIED_AS`：失败经验归类。
- `BUSINESS_ENTITY_RELATED_TO_API`：业务实体与 API 相关。
- `BUSINESS_ENTITY_REQUIRES_PRECONDITION`：业务实体依赖前置条件记忆。
- `SUITE_CONTAINS_CASE`：链路套件包含用例。
- `SUITE_STEP_PRODUCES_VARIABLE` / `SUITE_STEP_CONSUMES_VARIABLE`：SUITE step 与变量的生产、消费关系。
- `VARIABLE_EXTRACTED_FROM_SOURCE_PATH`：变量来自响应字段路径。
- `POLICY_REASON_APPLIES_TO_TOOL`：策略原因适用于某个工具。
- `FACT_HAS_TYPE` / `FACT_HAS_TAG`：事实类型和标签关系。
- `FACT_REINFORCES_FACT`：重复证据或相同事实指纹形成增强关系。
- `FACT_CONFLICTS_WITH_FACT`：身份冲突或语义冲突的审计关系，只用于显式审计查询，不进入默认正向扩展。

## 5. Provenance 与置信治理

每个节点和边都保留来源证据：

- `sourceMemoryIds`：来自哪些长期记忆。
- `factFingerprints`：来自哪些事实指纹。
- `sourceRefs`：来自哪些任务、反馈、执行记录或人工确认。
- `evidenceSummaries`：可展示给 citation 的证据摘要。
- `occurrenceCount`：关系出现次数。
- `confidence`：基于 memory confidence、importance、successContribution、证据数量和人工确认信号计算。
- `firstSeenAt` / `lastSeenAt`：关系首次和最近出现时间。

默认查询只返回达到 confidence gate 的正向关系。低置信关系和 conflict 关系不会自动进入 Unified Context，避免弱关系污染 prompt。

## 6. Query 与 Retrieval

Memory Graph 查询服务支持从实体种子扩展相关实体和相关长期记忆：

- 从 `apiPath` / `httpMethod` 扩展 module、system、errorCode 和 failureClassification。
- 从 `errorCode` 扩展 API、module 和相关失败记忆。
- 从 `businessEntity` 扩展 API、前置条件和失败经验。
- 从 suite scoped `variableKey` 扩展 producer step、consumer step、sourcePath 和相关 SUITE 失败记忆。
- 从 `policyReason` 扩展 toolName 和 policy learning 记忆。
- 从 tag / factType 扩展相关长期事实。

Long-term Memory Retrieval 的读路径保持先语义召回、再图谱扩展：

```text
pgvector / metadata candidates
-> extract graph seed entities
-> query Memory Entity Graph
-> append graph-expanded memory hits
-> dedupe by memoryId
-> apply limit and token budget
```

graph-expanded hit 使用 `retrievalChannel=graph`，并带上 `graphMatchReason`、`graphRelationPath`、`graphRelationConfidence`、`graphSourceMemoryIds`、`graphSourceRefs`、`graphFactFingerprints` 和 `graphEvidenceSummaries`。

## 7. Unified Context 接入

Unified Context Builder 不需要知道图谱如何投影，只消费 Long-term Memory Retrieval 返回的 hit。普通 pgvector 命中和 graph-expanded 命中会一起进入 `ContextBundle.longTermMemoryContext()`，再统一接受 Context token budget 裁剪。

进入 citation 时，graph-expanded memory 会暴露：

- channel：`retrievalChannel=graph`。
- match reason：例如 `graph-related-error-code`、`graph-related-api`、`graph-related-variable`、`graph-related-policy`、`graph-related-business-entity`。
- relation path：解释从种子实体到相关记忆的关系路径。
- relation confidence：解释为什么这条图关系可信。
- source memory ids / source refs：解释关系来自哪些长期记忆和证据来源。
- fact fingerprints / evidence summaries：解释事实指纹和证据摘要。

这样 Planner、用例生成、失败分析、报告生成等上层能力看到的不只是“这条记忆相似”，还可以看到“这条记忆是因为 PAY_401 在同一个 charge API 上被观察到，所以被图谱扩展进来”。

## 8. 与 Knowledge RAG 的边界

Memory Graph 和 Knowledge RAG 是两个不同来源、不同职责的上下文系统：

- Knowledge RAG 服务人类文档检索，处理接口说明、规范、wiki、错误码文档、环境说明等项目文档。
- Memory Graph 服务运行经验关系推理，处理 Agent 执行后沉淀的长期经验事实之间的实体关系。

V5-3 不改变 Knowledge RAG 的 chunking、embedding、document retrieval 职责，不把 Knowledge Document 当作 graph source of truth，也不让 Memory Graph 替代文档检索。

## 9. 与 mem0 / Viking / 外部图数据库的关系

ProbeFlow 借鉴 mem0 的思想：记忆不是日志仓库，而是经过 extract、quality gate、dedup、merge、evidence ledger 和 identity governance 后的长期事实。

ProbeFlow 借鉴 Viking 的思想：Planner、用例生成、失败分析不各自拼 prompt，而是通过 Unified Context 统一组装、解释来源、控制预算。

但 V5-3 不引入 mem0 SDK，不依赖 VikingDB，不接 Neo4j 或其他外部图数据库。当前图谱使用现有数据库表和 JPA repository 实现，保证本地与 CI 可稳定运行。

## 10. 明确不做的内容

V5-3 只做 Memory Entity Graph，不实现以下后续阶段能力：

- Query Rewrite。
- 多路召回。
- RRF。
- Cross Encoder Rerank。
- LLM Rerank。
- Small-to-Big。
- 父子索引。
- 完整 GraphRAG 产品化。
- 图谱可视化 UI。

这些能力可以在 V5-4 / V5-5 继续构建，但不能混入 V5-3 的阶段验收。

## 11. 面试讲解主线

可以用一句话概括 V5-3：

ProbeFlow 先把执行经验通过 Memory Refinery 提纯成干净的 Long-term Memory，再把这些事实投影成 Memory Entity Graph，最后让 Unified Context 使用图谱扩展出来的相关经验，并在 citation 中解释关系路径、置信度和证据来源。

讲解顺序建议：

```text
Memory Candidate
-> Memory Fact
-> Long-term Memory
-> Memory Entity Graph
-> Graph-expanded Unified Context
```

这条主线能说明 ProbeFlow 的 Agent 学习不是“把历史塞给模型”，而是“事实提纯 + 关系投影 + 可解释上下文”的工程化记忆系统。
