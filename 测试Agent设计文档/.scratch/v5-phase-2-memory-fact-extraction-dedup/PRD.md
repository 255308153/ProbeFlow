状态：ready-for-agent

# ProbeFlow V5-2：Memory Fact Extraction 与 Dedup PRD

## Problem Statement

ProbeFlow 已经完成 V5-1 的真实 Embedding Provider、embedding profile、pgvector 召回、Knowledge RAG 与 Long-term Memory 的语义检索地基。现在系统已经能把文档知识和长期经验写成向量，并通过 pgvector 参与 Unified Context。

但当前 Memory 写路径仍然偏“候选文本压缩 + 启发式分类 + 简单去重合并”。它可以证明长期记忆链路存在，却还不能充分体现用户最想在面试中讲清楚的 Agent 设计能力：

- Agent 不是把日志全文、对话全文、工具输出全文都塞进长期记忆。
- Agent 应该从候选材料中抽取可复用的事实。
- Agent 应该判断事实类型、适用范围、证据来源、置信度和重要性。
- Agent 应该拒绝一次性噪音、任务局部信息、过泛结论和敏感内容。
- Agent 应该把重复事实合并，而不是让长期记忆越来越脏。
- Agent 应该识别冲突事实，避免不同系统、不同接口、不同错误码的经验被错误合并。
- Agent 应该保留 evidence ledger，让每条长期记忆都能追溯“从哪里来、为何可信、被合并过几次”。

用户明确希望 ProbeFlow 不直接依赖 mem0 或 VikingDB，而是借鉴它们的思想自研：

- 借鉴 mem0：Memory 写入前先做 extract -> classify -> dedup -> merge -> score -> store。
- 借鉴 Viking：Memory、RAG、Task State、ApiSpec 最终要进入统一上下文，而不是各模块各自拼 prompt。

V5-2 要补上的正是 Memory 写路径的核心：把“候选记忆”变成“可解释、可追溯、可去重、可召回的长期事实记忆”。如果不做这一层，后续即使有真实向量召回，也会召回大量质量不稳定的经验，RAG / Memory 完整版会缺少可信基础。

## Solution

新增 V5-2 Memory Fact Extraction 与 Dedup 能力，让 Memory Refinery 从“压缩候选文本”升级为“事实提纯引擎”。

核心链路为：

```text
Agent Memory Candidate
-> Sanitization
-> Fact Extraction
-> Fact Classification
-> Quality Gate
-> Dedup / Merge / Conflict Check
-> Evidence Ledger
-> Confidence / Importance / Success Scoring
-> Long-term Memory
-> V5-1 Embedding + pgvector
-> Unified Context
```

V5-2 的目标不是接入 mem0 SDK，也不是接入 VikingDB；目标是把 ProbeFlow 自己的 Memory Engine 做成可讲、可测、可迭代的内部模块。

第一，候选记忆必须先抽取为 Memory Fact。

候选来源可以是失败分析、StepOutcome、人工决策、策略学习、套件失败分析、执行观察、用户反馈等。候选文本进入 Memory Refinery 后，不应直接被压缩成长期记忆，而应先抽取出一个或多个结构化事实。

每个事实至少需要表达：

- 事实类型。
- 摘要。
- 规范化内容。
- 适用范围。
- 触发条件。
- 来源证据。
- 置信度。
- 重要性。
- 可复用程度。
- 敏感信息状态。
- 质量门禁结果。

第二，事实类型要服务 API 测试 Agent 的真实场景。

V5-2 至少覆盖这些 Memory Fact 类型：

- `failure_pattern`：某类失败的高频根因或恢复经验。
- `testing_pattern`：某类接口、字段、状态流转的测试策略。
- `project_knowledge`：项目级稳定事实，例如鉴权、租户、模块规则、错误码语义。
- `preference`：用户或团队对输出风格、用例生成、报告内容的偏好。
- `policy_learning`：Planner / Policy Validator 从拒绝、放行、人工审批中学到的安全经验。
- `suite_dependency_fact`：SUITE 链路中上游步骤、变量提取、下游消费之间的稳定关系。
- `variable_extraction_fact`：某响应字段可作为后续请求变量的经验。
- `business_precondition_fact`：业务链路执行前必须满足的状态、前置数据或权限条件。

第三，质量门禁要主动防止 Memory Pollution。

Memory Fact 进入长期记忆前必须经过质量门禁。系统要拒绝：

- 空候选。
- 置信度过低的候选。
- 一次性报错。
- 只适用于当前任务的临时信息。
- 无可复用事实的描述。
- 没有证据来源的结论。
- 太泛、无法指导后续任务的结论。
- 只包含原始日志、堆栈、临时调试输出的内容。
- sanitization 后仍包含敏感信息的内容。
- 身份线索冲突的事实，例如 system、module、apiPath、errorCode、business entity 不一致。

第四，去重合并要从“文本相似”升级为“事实身份 + 证据增强”。

V5-2 的 dedup 不能只看 summary / content 是否相似。它应该综合：

- exact source idempotency。
- fact fingerprint。
- fact type。
- system / module / apiPath / httpMethod / errorCode / business entity。
- sourceType / sourceRef / taskId。
- tags。
- evidence hash。
- 语义或文本相似度。
- 关键 metadata 是否一致。

如果是同一个事实，系统应该合并证据、增强置信度、提升重要性、累加 evidenceCount、记录 mergedSourceRefs 和 mergedSourceTypes，并重新写入 V5-1 embedding profile 下的向量。

如果看起来相似但关键身份冲突，系统不能合并，必须拒绝或标记为 conflict candidate。

第五，Evidence Ledger 要成为长期记忆可解释性的基础。

每条长期记忆都必须能回答：

- 这条记忆来自哪个任务。
- 来自哪个来源类型。
- 来自哪个来源引用。
- 是否来自失败分析、人工反馈、StepOutcome、策略学习或 SUITE 分析。
- 原始证据摘要是什么。
- sanitization 后保留了什么证据。
- 被合并过几次。
- 合并了哪些 sourceRef。
- 哪些 evidence 支撑了当前置信度。
- 是否经历过冲突判断。
- 是否由人工确认增强。

V5-2 不要求做复杂 UI，但 metadata / audit summary 必须能承载这些解释信息。

第六，Fact Extraction 要兼容 deterministic 默认测试与 LLM 增强。

ProbeFlow 已经有 LLM 接入基础，但默认测试不能依赖真实外部模型。V5-2 需要采用可控策略：

- 默认使用 deterministic / fake-friendly 的事实抽取路径，保证 `mvn test` 稳定。
- 可以预留 LLM-assisted fact extraction seam，让后续真实 LLM 手动实验使用同一合同。
- LLM 输出必须经过 schema validation、sanitization、quality gate 和 dedup，不能因为是 LLM 输出就直接入库。
- LLM 不可用时默认测试不失败；真实 LLM 模式只作为显式配置或手动验证。

第七，Memory Refinery 仍然是唯一长期记忆写入入口。

V5-2 不应该让失败分析、人工反馈、执行引擎、Planner 各自直接写 Long-term Memory。所有进入长期记忆的内容都必须经过 Memory Refinery 的事实抽取、质量门禁、去重合并、证据账本和 embedding 写入。

最高测试 seam 采用 Memory Refinery。辅助测试 seam 采用 Agent Memory Feedback 的应用层入口，验证真实候选来源能正确进入 fact-aware refinery。

## User Stories

1. As an API 测试 Agent 用户, I want Agent 从候选记忆中抽取结构化事实, so that 长期记忆保存的是可复用经验而不是原始噪音。
2. As an API 测试 Agent 用户, I want 失败分析候选被抽取为 failure pattern, so that 后续相似失败能召回历史根因。
3. As an API 测试 Agent 用户, I want 测试策略候选被抽取为 testing pattern, so that 后续生成用例时能复用有效测试经验。
4. As an API 测试 Agent 用户, I want 项目稳定规则被抽取为 project knowledge, so that Agent 能记住项目鉴权、租户、模块和错误码知识。
5. As an API 测试 Agent 用户, I want 人工修订和确认能沉淀为 preference, so that Agent 后续输出更符合我的测试风格。
6. As an API 测试 Agent 用户, I want Policy Validator 的拒绝和放行经验能沉淀为 policy learning, so that Planner 后续更少做高风险动作。
7. As an API 测试 Agent 用户, I want SUITE 失败中的上游下游关系能沉淀为 suite dependency fact, so that 链路用例后续更容易恢复。
8. As an API 测试 Agent 用户, I want 变量提取失败能沉淀为 variable extraction fact, so that 后续链路能优先使用正确响应字段。
9. As an API 测试 Agent 用户, I want 业务前置条件能沉淀为 business precondition fact, so that Agent 执行链路前知道需要准备什么状态。
10. As an API 测试 Agent 用户, I want 一条候选里能抽取多条事实, so that 复杂失败分析不会被压成一个含混总结。
11. As an API 测试 Agent 用户, I want 每条事实都有 fact type, so that 检索和上下文构建可以按类型选择。
12. As an API 测试 Agent 用户, I want 每条事实都有适用范围, so that 支付模块经验不会误用于用户模块。
13. As an API 测试 Agent 用户, I want 每条事实都有触发条件, so that Agent 知道什么时候该使用这条记忆。
14. As an API 测试 Agent 用户, I want 每条事实都有证据摘要, so that 我能解释这条记忆为什么存在。
15. As an API 测试 Agent 用户, I want 每条事实都有来源任务, so that 我能追溯它来自哪次执行。
16. As an API 测试 Agent 用户, I want 每条事实都有 sourceType 和 sourceRef, so that 我能定位原始来源。
17. As an API 测试 Agent 用户, I want 每条事实都记录 sanitized evidence, so that 记忆可解释但不泄漏敏感信息。
18. As an API 测试 Agent 用户, I want 低置信度候选被拒绝, so that 长期记忆不会被不可靠经验污染。
19. As an API 测试 Agent 用户, I want 一次性临时报错被拒绝, so that Memory 不会记住无复用价值的偶发现象。
20. As an API 测试 Agent 用户, I want task-local only 信息被拒绝, so that 当前任务临时状态不会污染跨任务记忆。
21. As an API 测试 Agent 用户, I want 没有证据的结论被拒绝, so that Memory 不会保存无法追溯的猜测。
22. As an API 测试 Agent 用户, I want 太泛的结论被拒绝, so that “注意失败”这类空话不会进入长期记忆。
23. As an API 测试 Agent 用户, I want sanitization 后仍有敏感内容的候选被拒绝, so that token、密码、cookie 不会进入长期记忆。
24. As an API 测试 Agent 用户, I want 重复提交同一个 sourceRef 时保持幂等, so that retry 不会制造重复记忆。
25. As an API 测试 Agent 用户, I want 相同 fact fingerprint 的候选被合并, so that 同一个经验只保留一条增强后的记忆。
26. As an API 测试 Agent 用户, I want 相同错误码和接口的失败经验被合并, so that Memory 能积累证据而不是膨胀。
27. As an API 测试 Agent 用户, I want 相同变量依赖关系被合并, so that SUITE 链路知识越用越可信。
28. As an API 测试 Agent 用户, I want 重复事实合并后累加 evidenceCount, so that 记忆强度能反映证据数量。
29. As an API 测试 Agent 用户, I want 重复事实合并后记录 mergedSourceRefs, so that 我能看到它由哪些来源支撑。
30. As an API 测试 Agent 用户, I want 重复事实合并后提升 confidence, so that 多次验证过的经验在召回中更可信。
31. As an API 测试 Agent 用户, I want 重复事实合并后提升 importance, so that 高频有用经验更容易被上下文选择。
32. As an API 测试 Agent 用户, I want 人工确认过的事实获得额外可信度, so that 人类反馈能真正影响 Memory。
33. As an API 测试 Agent 用户, I want 冲突系统名的候选不能合并, so that A 系统经验不会污染 B 系统。
34. As an API 测试 Agent 用户, I want 冲突模块名的候选不能合并, so that 不同模块的相似错误不会被误认为同一事实。
35. As an API 测试 Agent 用户, I want 冲突 apiPath 的候选不能合并, so that 相似接口的不同规则不会互相覆盖。
36. As an API 测试 Agent 用户, I want 冲突 errorCode 的候选不能合并, so that 不同错误码语义不会混淆。
37. As an API 测试 Agent 用户, I want 冲突 business entity 的候选不能合并, so that 订单、支付、库存经验不会乱用。
38. As an API 测试 Agent 用户, I want 冲突候选有明确 rejection reason, so that 我知道它为什么没有合并。
39. As an API 测试 Agent 用户, I want 记忆合并后重新生成 embedding, so that pgvector 召回使用的是最新合并内容。
40. As an API 测试 Agent 用户, I want embedding profile metadata 继续保留, so that V5-1 的 reindex guard 不会被破坏。
41. As an API 测试 Agent 用户, I want Memory Fact 的 tags 自动补全, so that 检索可以使用 auth、tenant、payment、retry 等标签。
42. As an API 测试 Agent 用户, I want metadata identity hints 自动保留, so that system、module、apiPath、errorCode 能参与 dedup。
43. As an API 测试 Agent 用户, I want raw evidence 只以安全摘要形式进入 metadata, so that 可追溯和安全可以兼得。
44. As an API 测试 Agent 用户, I want Agent Memory Feedback 的候选仍被审计, so that intake、pending、accepted、duplicate、rejected 的状态可见。
45. As an API 测试 Agent 用户, I want 失败分析候选通过应用层入口进入 Memory Refinery, so that 所有写入走同一规则。
46. As an API 测试 Agent 用户, I want StepOutcome 候选通过应用层入口进入 Memory Refinery, so that 编排失败也能转为长期经验。
47. As an API 测试 Agent 用户, I want Human Decision 候选通过应用层入口进入 Memory Refinery, so that 人工反馈能参与长期学习。
48. As an API 测试 Agent 用户, I want Policy Learning Note 候选通过应用层入口进入 Memory Refinery, so that Planner 策略能被持续优化。
49. As an API 测试 Agent 用户, I want Suite Failure 候选通过应用层入口进入 Memory Refinery, so that 链路失败经验能沉淀。
50. As an API 测试 Agent 用户, I want 默认测试使用 deterministic fact extractor, so that 没有真实 LLM key 也能稳定验证。
51. As an API 测试 Agent 用户, I want LLM-assisted extractor 只能显式启用, so that 默认 CI 不产生外部依赖。
52. As an API 测试 Agent 用户, I want LLM 输出经过 schema validation, so that 非法 JSON 或缺字段不会进入 Memory。
53. As an API 测试 Agent 用户, I want LLM 输出仍经过 quality gate, so that LLM 幻觉不会直接污染长期记忆。
54. As an API 测试 Agent 用户, I want fact extraction 的拒绝原因可见, so that 我能调试为什么候选没进长期记忆。
55. As an API 测试 Agent 用户, I want accepted fact 的 audit summary 可见, so that 我能解释它如何被抽取、分类和评分。
56. As an API 测试 Agent 用户, I want merged fact 的 audit summary 可见, so that 我能解释它如何增强已有记忆。
57. As an API 测试 Agent 用户, I want duplicate fact 不重复写库, so that Memory 数量不会因为重试暴涨。
58. As an API 测试 Agent 用户, I want 记忆状态仍支持 ACTIVE、INACTIVE、ARCHIVED, so that 低质量或过时记忆可以退出召回。
59. As an API 测试 Agent 用户, I want Long-term Memory retrieval 能消费新的 fact metadata, so that 后续上下文更容易解释。
60. As an API 测试 Agent 用户, I want Unified Context 能保留 fact evidence summary, so that Agent 使用记忆时能引用证据。
61. As an API 测试 Agent 用户, I want V5-2 不直接实现 entity graph, so that 当前阶段聚焦 Memory 写路径。
62. As an API 测试 Agent 用户, I want V5-2 不直接实现 Query Rewrite, so that RAG 读路径增强留到 V5-4。
63. As an API 测试 Agent 用户, I want V5-2 不直接实现 rerank, so that 排序增强留到 V5-5。
64. As an API 测试 Agent 用户, I want V5-2 不直接实现 Small-to-Big, so that 父子索引留到 RAG Pro 阶段。
65. As an API 测试 Agent 用户, I want V5-2 不引入 mem0 SDK, so that ProbeFlow 的记忆提纯设计可以自研讲解。
66. As an API 测试 Agent 用户, I want V5-2 不引入 VikingDB, so that ProbeFlow 的统一上下文设计不绑定外部数据库产品。
67. As an API 测试 Agent 用户, I want README 或阶段文档说明 Memory Fact pipeline, so that 同事能理解这不是普通 CRUD。
68. As an API 测试 Agent 用户, I want 验收测试保护 Memory Refinery 是唯一长期记忆写入口, so that 后续模块不会绕过提纯。
69. As an API 测试 Agent 用户, I want 验收测试保护默认测试不调用真实 LLM, so that 本地和 CI 稳定。
70. As an API 测试 Agent 用户, I want 面试时能讲清 Memory 从候选到事实再到召回, so that 项目体现真正的 Agent 工程设计。

## Implementation Decisions

- V5-2 保持 Memory Refinery 作为长期记忆写入的唯一核心 seam。
- Agent Memory Feedback 继续作为候选进入系统的应用层入口，负责 intake、sanitization、idempotency、candidate record、audit summary 和调用 refinery。
- Memory Refinery 从“候选压缩器”升级为“事实提纯编排器”，内部流程按 fact extraction、classification、quality gate、dedup、merge/conflict、scoring、embedding write 组织。
- 引入明确的 Memory Fact 概念，用于表达从候选中提取出的结构化长期经验。Memory Fact 是进入 Long-term Memory 前的中间领域对象，不等同于最终持久化实体。
- Memory Fact 至少包含 fact type、summary、content、applicability、trigger、tags、identity hints、evidence entries、confidence、importance、reuse score、quality status 和 rejection reason。
- Fact type 覆盖 failure pattern、testing pattern、project knowledge、preference、policy learning、suite dependency fact、variable extraction fact、business precondition fact。
- Fact extraction 默认采用 deterministic / fake-friendly 实现，确保默认测试无需外部服务。
- 允许预留 LLM-assisted fact extractor seam，但必须显式启用，不进入默认 `mvn test`。
- LLM-assisted extractor 的输出必须经过 schema validation、sanitization、quality gate 和 dedup，不允许直接写长期记忆。
- Sanitization 在 fact extraction 前后都要生效：候选进入时清洗原始文本，事实成型后再次校验摘要、内容、证据和 metadata。
- Quality Gate 必须返回结构化 decision，包括 accepted、rejected、duplicate、merged、conflict 等外部可见状态。
- Quality Gate 的 rejection reason 至少覆盖 empty candidate、low confidence、one-off noise、task-local only、no reusable fact、missing evidence、too generic、sensitive content、identity conflict。
- Dedup 要使用 fact fingerprint，而不是只依赖 summary/content 相等。
- Fact fingerprint 应综合 fact type、normalized summary、normalized content、关键 metadata identity hints、source hints 和 evidence hints。
- Dedup 需要支持 exact source idempotency：同一 sourceType + sourceRef + taskId 的候选重复提交时不重复写长期记忆。
- Dedup 需要支持 same fact merge：不同来源描述同一事实时合并证据，而不是新增重复 memory。
- Dedup 需要支持 similarity candidate check：文本或语义相似但不是 exact fingerprint 时，只有 identity hints 不冲突才允许合并。
- Conflict Check 必须阻止 system、module、apiPath、httpMethod、errorCode、business entity、failure classification 等关键身份冲突的事实合并。
- 冲突候选可以被拒绝或作为 conflict audit 记录，但不能覆盖已有长期记忆。
- Evidence Ledger 优先存放在长期记忆 metadata 中，除非实现过程中发现需要独立表；V5-2 不主动扩大 schema 复杂度。
- Evidence Ledger 至少记录 taskId、sourceType、sourceRef、executionId、observationId、decisionId、suiteId、caseId、rootStepId、affectedDownstreamStepIds、failureClassification、rawEvidence hash/summary、sanitized evidence summary。
- 合并长期记忆时必须更新 evidenceCount、mergeCount、mergedSourceRefs、mergedSourceTypes、evidenceSummaries、lastMergedAt。
- 合并长期记忆时 confidence、importance、successContribution 应该增强，但要有上限，避免无限膨胀。
- 人工确认、人类修订、重复证据、高风险故障、明确业务身份可以提升 confidence 或 importance。
- 低置信度、缺证据、过泛内容、只适用于当前任务的内容不能通过简单 tag 伪装成长期记忆。
- Long-term Memory 仍作为最终长期记忆存储，V5-2 不替换其持久化模型。
- 如确实需要 schema 扩展，应优先扩展 metadata 或增加最小必要字段，不为 V5-3 的 entity graph 提前建复杂表。
- Memory 写入 embedding 必须继续复用 V5-1 的 EmbeddingService、embedding profile metadata 和 vector validation。
- 当长期记忆内容因合并发生变化时，需要重新生成 embedding，保证 pgvector 召回基于最新内容。
- Memory Retrieval 不作为 V5-2 主改造对象，但应能保留并透传新的 fact/evidence metadata，供 Unified Context 使用。
- Unified Context Builder 不需要理解 extractor 细节，只消费长期记忆命中中的 fact summary、scope type、evidence summary、confidence、importance 和 citation-like metadata。
- V5-2 文档需要说明 ProbeFlow 借鉴 mem0 的提纯思想，但不引入 mem0 依赖；借鉴 Viking 的统一上下文思想，但不引入 VikingDB 依赖。
- V5-2 文档需要说明后续路线：V5-3 做 Memory Entity Graph，V5-4 做 RAG Query Rewrite + Multi-route Retrieval，V5-5 做 Rerank + Small-to-Big。

## Testing Decisions

- 测试只验证外部行为，不测试 private helper、内部 token 相似度公式或具体字符串清洗实现。
- 最高测试 seam 是 Memory Refinery：给定 Memory Candidate，断言系统产出 accepted、rejected、merged、duplicate、conflict 等外部可见结果和长期记忆状态。
- 辅助测试 seam 是 Agent Memory Feedback：给定失败分析、StepOutcome、Human Decision、Policy Learning、Suite Failure 等真实来源，断言候选能经过 intake / sanitization / audit 后进入 fact-aware refinery。
- Memory Refinery 测试应覆盖：可复用失败候选被抽取为 failure pattern 并写入长期记忆。
- Memory Refinery 测试应覆盖：测试策略候选被抽取为 testing pattern 并保留 tags / applicability。
- Memory Refinery 测试应覆盖：项目规则候选被抽取为 project knowledge 并保留 system/module/apiPath 等 identity hints。
- Memory Refinery 测试应覆盖：人工反馈候选被抽取为 preference 并获得人工确认增强。
- Memory Refinery 测试应覆盖：policy learning 候选被抽取并保留 policy reason、tool name、risk level。
- Memory Refinery 测试应覆盖：suite dependency / variable extraction 候选被抽取并保留 root step、downstream steps、variable key、source path。
- Memory Refinery 测试应覆盖：低置信度候选被拒绝，且不写 Long-term Memory。
- Memory Refinery 测试应覆盖：one-off noise 被拒绝。
- Memory Refinery 测试应覆盖：task-local only 候选被拒绝。
- Memory Refinery 测试应覆盖：无证据或无可复用事实候选被拒绝。
- Memory Refinery 测试应覆盖：sanitization 后仍含敏感内容的候选被拒绝或被安全处理。
- Memory Refinery 测试应覆盖：同一 sourceType/sourceRef/taskId 重复提交保持幂等。
- Memory Refinery 测试应覆盖：相同 fact fingerprint 的候选合并到同一长期记忆。
- Memory Refinery 测试应覆盖：相同事实合并后 evidenceCount、mergeCount、mergedSourceRefs、confidence、importance 更新。
- Memory Refinery 测试应覆盖：关键 identity hints 冲突时不能合并，并返回 conflict / rejected reason。
- Memory Refinery 测试应覆盖：合并后重新写 embedding，且 embedding profile metadata 没有丢失。
- Agent Memory Feedback 测试应覆盖：失败分析候选经过应用层入口后进入 refinery 并产生 fact-aware 结果。
- Agent Memory Feedback 测试应覆盖：StepOutcome 候选经过应用层入口后不会绕过 sanitization 和 audit。
- Agent Memory Feedback 测试应覆盖：Human Decision 候选保留人工确认来源并参与评分。
- Agent Memory Feedback 测试应覆盖：Policy Learning Note 候选保留策略原因和工具风险信息。
- Agent Memory Feedback 测试应覆盖：Suite Failure 候选保留 executionId、suiteId、caseId、rootStepId、affectedDownstreamStepIds。
- 验收边界测试应确认没有引入 mem0 SDK 依赖。
- 验收边界测试应确认没有引入 VikingDB 依赖。
- 验收边界测试应确认默认测试不调用真实 LLM。
- 验收边界测试应确认 V5-2 没有实现 V5-3 entity graph。
- 验收边界测试应确认 V5-2 没有实现 V5-4 query rewrite / multi-route retrieval。
- 验收边界测试应确认 V5-2 没有实现 V5-5 rerank / Small-to-Big。
- Prior art 包括现有 Memory Refinery 测试、Long-term Memory Retrieval 测试、Unified Context semantic evidence 测试、Agent Memory Feedback candidate intake / closed loop / suite failure / human decision / policy note 测试、V5-1 embedding profile 与 pgvector 边界测试。
- 完整 `mvn test` 必须在无真实 LLM、无 mem0、无 VikingDB、无外部 embedding 服务的环境下通过。

## Out of Scope

- 不直接接入 mem0 SDK。
- 不直接接入 VikingDB。
- 不把 mem0 或 VikingDB 作为运行时依赖。
- 不做 V5-3 Memory Entity Graph。
- 不做实体关系图谱、图查询、图谱可视化。
- 不做 V5-4 RAG Query Rewrite。
- 不做多路召回编排、RRF 或 query expansion。
- 不做 V5-5 Cross-Encoder rerank。
- 不做 LLM rerank。
- 不做 Small-to-Big 父子索引。
- 不重写 Knowledge RAG 文档切片策略。
- 不重写 pgvector 检索地基。
- 不做生产级异步 memory compaction job。
- 不做 Memory 管理后台 UI。
- 不做跨项目多租户记忆隔离平台。
- 不把真实 LLM fact extraction 放进默认 CI。
- 不允许任何模块绕过 Memory Refinery 直接写长期记忆。
- 不改变 ProbeFlow 当前 HTTP API 测试主边界。

## Further Notes

V5-2 完成后，ProbeFlow 的 Memory 写路径可以这样讲：

```text
1. 候选记忆来自执行、失败分析、人工反馈、策略学习和套件分析。
2. 所有候选先进入 Agent Memory Feedback 做 intake、sanitization、audit 和幂等检查。
3. 所有长期写入统一进入 Memory Refinery。
4. Memory Refinery 不直接存原文，而是抽取 Memory Fact。
5. 每个 Memory Fact 经过分类、质量门禁、去重、冲突检查和证据账本构建。
6. 新事实写入 Long-term Memory；重复事实合并证据并增强评分。
7. 合并后的长期记忆重新生成 embedding，继续使用 V5-1 pgvector 召回。
8. Unified Context 后续消费的是带证据、置信度、重要性和来源的经验，而不是噪音日志。
```

V5 系列后续路线保持：

```text
V5-1: Real Embedding + pgvector Retrieval
V5-2: Memory Fact Extraction + Dedup
V5-3: Memory Entity Graph
V5-4: RAG Query Rewrite + Multi-route Retrieval
V5-5: Rerank + Small-to-Big
V5-6: Unified Context Engine Pro
```

这条路线能把用户最关心的“Agent 设计”讲完整：先有真实语义地基，再做记忆提纯，再做实体关系，再做高级召回，最后形成可解释的统一上下文引擎。
