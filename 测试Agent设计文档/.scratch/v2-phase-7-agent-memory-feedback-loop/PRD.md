状态：ready-for-agent

# ProbeFlow V2 Phase 7：Agent Memory Feedback Loop PRD

## Problem Statement

ProbeFlow V2 已经完成了 LLM Provider、Tool Contract / AgentPolicy、Controlled Planner、Policy Validator、Replanning Loop 和 Human-in-the-loop Agent Workflow。系统现在能让 Agent 做受控规划、被策略拦截、失败后重规划，并在不确定或高风险场景下停下来问人。

但现在 Agent 的“经验学习”还没有形成完整闭环。

项目里已经存在一些重要基础：

- `MemoryRefineryService` 可以把 `MemoryCandidateRequest` 提炼为长期记忆。
- `LongTermMemoryRetrievalService` 可以按结构、标签、向量和权重召回长期记忆。
- `UnifiedContextBuilder` 可以把长期记忆放进后续任务上下文。
- 失败分析已经能从高价值失败中生成长期记忆候选。
- Phase 6 已经能从人类 decision 生成 memory candidate handoff，但不会直接写长期记忆。

这些能力证明系统“可以存记忆”，但还没有把 Agent 运行过程中的反馈变成一个可审计、可治理、可复用的学习闭环：

- StepOutcome、Planner 被拒绝、Policy 拦截、人类采纳/拒绝、重复失败、执行结果之间还没有统一的 candidate intake。
- 人类反馈生成的 candidate 还没有正式进入 MemoryRefinery 的治理流程。
- 记忆是否有用、是否误导、是否应降权，还没有 usefulness feedback 机制。
- 重复失败和重复人类反馈还没有形成更强的 failure pattern / testing pattern。
- 置信度、重要性、成功贡献度的更新还主要发生在单次 refine / retrieval 内，还没有从后续任务反馈中反向校准。
- Planner、TestCaseGeneration、FailureAnalysis 等后续流程虽然可以通过 `UnifiedContextBuilder` 拿到长期记忆，但还没有证明“上一轮经验能影响下一轮 Agent 行为”。
- 记忆候选、记忆接受、记忆合并、记忆使用、记忆反馈之间缺少统一审计链。

从面试讲解角度，Phase 7 要回答一个更高级的问题：

```text
Agent 不是每次从零开始。
它如何从失败和人的选择里学习？
它如何避免把错误经验写进长期记忆？
它如何证明旧经验真的被下一次任务用上？
```

Phase 7 的目标不是做一个“黑盒自学习 LLM”，而是把 Agent Memory Feedback Loop 做成可信的后端 Agent Core：有候选、有审核、有合并、有置信度、有引用、有反馈、有边界。

## Solution

新增 Agent Memory Feedback Loop，把不同来源的运行经验统一接入 MemoryRefinery，并把长期记忆的使用效果反馈回记忆评分。

目标链路是：

```text
StepOutcome / ExecutionRecord / FailureAnalysis / HumanDecision / PolicyValidationResult / PlannerDecision
        |
        v
MemoryCandidate intake
        |
        v
Candidate audit + eligibility + sanitization
        |
        v
MemoryRefineryService
        |
        v
LongTermMemory create / merge / reject
        |
        v
UnifiedContextBuilder recall in next task
        |
        v
Planner / TestCaseGeneration / FailureAnalysis use citations
        |
        v
Usefulness feedback adjusts confidence / importance / successContribution
```

Phase 7 重点做后端应用层闭环：

- 建立统一的 AgentMemoryFeedback 入口，把多种 Agent 事件转成 `MemoryCandidateRequest` 或等价候选记录。
- 把 Phase 6 的 human feedback candidate 真正送入 MemoryRefinery，但仍要经过校验和审计。
- 从 StepOutcome / ExecutionRecord / FailureAnalysis 中提取高价值失败经验，复用已有 FailureAnalysis 与 MemoryRefinery 能力。
- 从 Planner 被 PolicyValidator 拒绝的决策中生成 policy learning note，帮助后续 Planner 避免类似越界建议。
- 从重复失败中合并 failure pattern，而不是堆积多条低质量记忆。
- 增加 memory usefulness feedback：被引用后如果帮助后续任务通过、帮助人类采纳、或被标记误导，应调整记忆 confidence、importance、successContribution 或 status。
- 让 `UnifiedContextBuilder` 的长期记忆引用能被后续流程审计，证明记忆被召回、被引用、被反馈。
- 保持“LLM 不能直接写长期记忆”的边界：LLM 可以参与总结，但最终写入必须经过 deterministic policy / MemoryRefinery。

建议最高测试接缝是 `AgentMemoryFeedbackApplicationService` 或等价应用层服务。它应作为 Phase 7 的主要入口，调用现有 `MemoryRefineryService`、`LongTermMemoryRetrievalService`、`UnifiedContextBuilder`、FailureAnalysis、HumanInTheLoop、PolicyValidator 相关模型。

测试不要绑定私有 helper 或文本拼接细节，而要断言外部行为：

- 候选是否被接受 / 拒绝 / 合并。
- 长期记忆字段是否合理。
- 置信度、重要性、成功贡献度是否按反馈变化。
- 敏感信息是否被脱敏。
- 旧记忆是否能被下一次 context build 召回。
- 引用后的 usefulness feedback 是否能更新 memory。
- 不合格候选是否不会污染长期记忆。

## User Stories

1. As an Agent 系统开发者, I want a unified memory feedback application service, so that 不同来源的 Agent 经验可以通过同一个入口进入学习闭环。
2. As an Agent 系统开发者, I want memory candidate intake from StepOutcome, so that 失败步骤可以变成可审计的候选经验。
3. As an Agent 系统开发者, I want memory candidate intake from ExecutionRecord, so that HTTP 执行结果中的失败模式可以沉淀。
4. As an Agent 系统开发者, I want memory candidate intake from FailureAnalysis, so that 已有失败分类能力可以继续复用。
5. As an Agent 系统开发者, I want memory candidate intake from HumanDecisionRecord, so that 人类采纳、拒绝、补充输入可以成为学习材料。
6. As an Agent 系统开发者, I want memory candidate intake from PolicyValidationResult, so that 被安全策略拒绝的 Planner 决策可以变成 policy learning note。
7. As an Agent 系统开发者, I want memory candidate intake from PlannerDecision, so that Planner 的成功和失败选择可以被复盘。
8. As an Agent 系统开发者, I want candidate source type recorded, so that 每条记忆都能回溯来源。
9. As an Agent 系统开发者, I want candidate source ref recorded, so that 可以定位原始 execution、decision、step 或 policy result。
10. As an Agent 系统开发者, I want candidate task id recorded, so that 记忆可以和任务时间线关联。
11. As an Agent 系统开发者, I want candidate evidence stored in sanitized form, so that 审计时不会泄露 token、cookie、password 或 authorization。
12. As an Agent 系统开发者, I want raw observation preserved separately, so that 原始 Observation 不会因为记忆合并被删除。
13. As an Agent 系统开发者, I want candidate eligibility rules, so that 低价值噪音不会进入长期记忆。
14. As an Agent 系统开发者, I want low-confidence candidate rejection, so that 猜测性经验不会误导后续 Agent。
15. As an Agent 系统开发者, I want noisy passed execution rejection, so that 正常成功日志不会污染长期记忆。
16. As an Agent 系统开发者, I want one-off low-risk failure rejection, so that 单次偶然失败不会被过度学习。
17. As an Agent 系统开发者, I want high-risk repeated failure acceptance, so that 真正重要的失败模式可以复用。
18. As an Agent 系统开发者, I want human-approved feedback to have higher confidence, so that 人类明确采纳的经验更容易被召回。
19. As an Agent 系统开发者, I want human-rejected feedback to be captured carefully, so that Agent 能学习哪些建议不该再提。
20. As an Agent 系统开发者, I want policy rejection notes to be stored as policy learning memories, so that Planner 未来能避开类似越界行为。
21. As an Agent 系统开发者, I want repeated blocker resolution feedback merged, so that 同类环境缺口形成稳定处理经验。
22. As an Agent 系统开发者, I want repeated draft review feedback merged, so that 用例生成偏好可以长期保留。
23. As an Agent 系统开发者, I want repeated high-risk rejection feedback merged, so that Agent 对危险动作越来越谨慎。
24. As an Agent 系统开发者, I want memory scope classification, so that 候选可以被分为 failure pattern、testing pattern、preference、project knowledge 或 policy learning note。
25. As an Agent 系统开发者, I want memory tags derived consistently, so that 后续召回可以按 stage、module、api、error code 和反馈类型过滤。
26. As an Agent 系统开发者, I want memory metadata to include system name, module, api path and error code when available, so that 结构化召回更精准。
27. As an Agent 系统开发者, I want candidate deduplication, so that 相同来源不会重复写入长期记忆。
28. As an Agent 系统开发者, I want semantic merge for similar candidates, so that 同类经验合并成一条更强的记忆。
29. As an Agent 系统开发者, I want merged source refs recorded, so that 合并后的记忆仍可追踪证据来源。
30. As an Agent 系统开发者, I want merge count recorded, so that 可以知道某个模式出现过多少次。
31. As an Agent 系统开发者, I want confidence updated on merge, so that 重复证据能提升记忆可信度。
32. As an Agent 系统开发者, I want confidence not to exceed safe bounds, so that 单类反馈不会把错误经验无限放大。
33. As an Agent 系统开发者, I want importance updated from risk and recurrence, so that 高风险高频经验更容易被召回。
34. As an Agent 系统开发者, I want success contribution updated from downstream outcomes, so that 真正有用的记忆排名更高。
35. As an Agent 系统开发者, I want negative usefulness feedback, so that 被证明误导的记忆可以降权。
36. As an Agent 系统开发者, I want positive usefulness feedback, so that 帮助任务成功的记忆可以升权。
37. As an Agent 系统开发者, I want neutral usefulness feedback, so that 无法判断效果的使用不会剧烈改变评分。
38. As an Agent 系统开发者, I want memory usage records, so that 每次长期记忆被召回都可以审计。
39. As an Agent 系统开发者, I want memory citation handoff from UnifiedContextBuilder, so that Planner 和生成流程知道使用了哪些记忆。
40. As an Agent 系统开发者, I want Planner input to include relevant long-term memory, so that Planner 能避免重复犯错。
41. As an Agent 系统开发者, I want TestCaseGeneration to receive testing pattern memories, so that 生成用例时能复用历史偏好。
42. As an Agent 系统开发者, I want FailureAnalysis to receive failure pattern memories, so that 分析失败时能对照历史问题。
43. As an Agent 系统开发者, I want PolicyValidator-related memories to be visible to planner context, so that 规划阶段可以提前避开策略雷区。
44. As an Agent 系统开发者, I want memory recall to respect token budget, so that 长期记忆不会挤掉更重要上下文。
45. As an Agent 系统开发者, I want memory recall to respect stage profile, so that 不同阶段看到不同类型经验。
46. As an Agent 系统开发者, I want memory recall to respect API/module filters, so that 无关经验不会污染当前任务。
47. As an Agent 系统开发者, I want low-confidence recalled memory marked, so that 下游流程能谨慎使用。
48. As an Agent 系统开发者, I want stale memory to be deactivated or downgraded, so that 过时经验不会长期误导。
49. As an Agent 系统开发者, I want archived memory not to be recalled, so that 人工或策略下线的记忆不会继续影响任务。
50. As an Agent 系统开发者, I want memory status transition audit, so that active、inactive、archived 的变化有记录。
51. As an Agent 系统开发者, I want memory feedback to be idempotent, so that 重复提交同一 feedback 不会重复升降权。
52. As an Agent 系统开发者, I want candidate refinement to be idempotent by source ref, so that 重试不会创建重复长期记忆。
53. As an Agent 系统开发者, I want transaction boundaries around refinement, so that candidate、memory、usage、feedback 不会半更新。
54. As an Agent 系统开发者, I want LLM summaries to be optional, so that CI 不依赖真实 LLM。
55. As an Agent 系统开发者, I want fake provider compatibility, so that memory loop 测试稳定可重复。
56. As an Agent 系统开发者, I want deterministic fallback summaries, so that 没有 LLM 时仍可生成候选记忆。
57. As an Agent 系统开发者, I want no direct LLM writes to LongTermMemory, so that 长期记忆必须经过治理。
58. As an Agent 系统开发者, I want no automatic deletion of original observations, so that 审计链完整保留。
59. As an Agent 系统开发者, I want memory candidate rejection reason, so that 可以解释为什么没有学习某条经验。
60. As an Agent 系统开发者, I want memory candidate result summary, so that 报告层以后可以展示学习结果。
61. As an Agent 系统开发者, I want task metadata to reference memory feedback results, so that 任务时间线能看到学习动作。
62. As an Agent 系统开发者, I want report generation to reuse memory feedback summary later, so that V3/V4 可以展示 Agent 学到了什么。
63. As an Agent 系统开发者, I want memory candidate generation from human promote draft, so that 好用例模式可以复用。
64. As an Agent 系统开发者, I want memory candidate generation from human discard draft, so that 低质量用例模式可以避免。
65. As an Agent 系统开发者, I want memory candidate generation from request changes, so that 人类修改偏好可以进入生成策略。
66. As an Agent 系统开发者, I want memory candidate generation from blocker resolution, so that 环境补充经验可以复用。
67. As an Agent 系统开发者, I want memory candidate generation from planner clarification, so that 常见澄清答案可以复用。
68. As an Agent 系统开发者, I want memory candidate generation from high-risk rejection, so that 高风险边界能被记住。
69. As an Agent 系统开发者, I want memory loop tests to prove next-task recall, so that 不是只测试写入数据库。
70. As an Agent 系统开发者, I want memory loop tests to prove downstream usefulness feedback, so that 记忆评分不是静态字段。
71. As an Agent 系统开发者, I want Phase 7 to remain backend-only, so that Agent Core 先稳定再做产品 UI。
72. As an Agent 系统开发者, I want Phase 7 to be explainable in interviews, so that 可以讲清楚可信 Agent 如何学习、如何避免乱学、如何证明有用。

## Implementation Decisions

- 新增 Agent Memory Feedback Loop 后端应用层模块，建议入口为 `AgentMemoryFeedbackApplicationService` 或等价服务。
- 复用现有 `MemoryCandidateRequest`、`MemoryRefineryService`、`LongTermMemoryRetrievalService`、`UnifiedContextBuilder` 作为核心治理和召回基础。
- Phase 7 不替换现有 MemoryRefinery，而是在它前后增加 candidate intake、eligibility、usage feedback 和 audit handoff。
- 可以新增 `MemoryCandidateRecord` 或等价持久化模型，记录 candidate intake、来源、状态、拒绝原因、refinery result、关联 memory id 和审计摘要。
- 可以新增 `MemoryUsageRecord` 或等价记录，保存长期记忆在 Planner、TestCaseGeneration、FailureAnalysis、Report 等阶段被召回和引用的事实。
- 可以新增 `MemoryUsefulnessFeedback` 或等价模型，表示某次 memory usage 对后续任务是 positive、negative、neutral 或 unknown。
- Memory candidate status 至少应表达 pending、accepted、rejected、merged、duplicate、failed 或等价状态。
- Memory feedback status 至少应表达 recorded、duplicate、rejected 或等价状态。
- 候选来源至少覆盖 StepOutcome、ExecutionRecord、FailureAnalysis、HumanDecisionRecord、PolicyValidationResult、PlannerDecision 和 manual/system source。
- source type 可以扩展现有枚举，也可以用 metadata 表达更细来源；但外部结果必须能区分失败分析、人类反馈、策略拒绝、Planner 决策。
- 长期记忆 scope 继续优先复用现有 failure pattern、testing pattern、preference、project knowledge；如 policy learning note 无法自然表达，可以新增明确 scope。
- 所有候选写入前必须做敏感信息脱敏；审计摘要、长期记忆内容和 metadata 都不能含明文 secret。
- LLM 可以用于候选摘要生成，但必须可关闭；CI 必须使用 fake provider 或 deterministic fallback。
- LLM 不能直接写 LongTermMemory；最终写入仍必须经过 MemoryRefinery / deterministic eligibility。
- Phase 6 的 human feedback candidate handoff 应被 Agent Memory Feedback Loop 消费，并真正进入 MemoryRefinery。
- FailureAnalysis 已有的长期记忆 refine 能力应保留；Phase 7 可以把它纳入统一审计结果，不应破坏现有测试。
- PolicyValidator 拒绝 Planner 决策时，可以生成 policy learning note candidate，记录被拒绝动作、风险、策略原因和改进建议。
- Planner clarification / high-risk rejection / request changes 等人类反馈应转为 preference、testing pattern 或 policy learning note。
- 重复 failure pattern 应通过 existing refinery merge 或新的 candidate record 合并逻辑增强，而不是创建多条散乱记忆。
- Confidence 更新应考虑初始来源、人类确认、重复次数、后续成功、后续失败、负反馈和过期程度。
- Importance 更新应考虑风险等级、影响范围、重复次数、是否影响关键 API、是否来自人类明确反馈。
- SuccessContribution 更新应来自后续任务引用后的结果，而不是仅由候选生成时决定。
- UnifiedContextBuilder 应继续负责把长期记忆组装进 context bundle；Phase 7 可以增强 citation / usage handoff，但不要重写上下文系统。
- Planner、TestCaseGeneration、FailureAnalysis 的接入应保持有限：它们消费 context / citations，不直接控制长期记忆写入。
- 如果新增表，必须提供 Flyway migration，并保持 H2 test profile 可迁移。
- 所有 candidate refine、usage feedback、memory scoring 更新应在事务边界内完成。
- Phase 7 不引入 REST Controller、前端、队列 worker、外部通知或真实生产 LLM 依赖。

## Testing Decisions

- 最高测试接缝是 `AgentMemoryFeedbackApplicationService` 或等价应用层服务；优先通过它验证端到端行为。
- 现有 `MemoryRefineryServiceTests`、`LongTermMemoryRetrievalServiceTests`、`UnifiedContextBuilderTests`、FailureAnalysis 相关测试是重要先例。
- 测试应该断言外部行为：candidate result、long-term memory 记录、metadata、confidence、importance、successContribution、usage record、feedback result、context recall 和审计摘要。
- 测试不要断言私有 helper、字符串拼接顺序、内部排序细节或具体实现类调用顺序。
- 需要测试 StepOutcome / ExecutionRecord / FailureAnalysis 到 memory candidate 的路径。
- 需要测试 HumanDecisionRecord 到 MemoryRefinery 的路径，证明 Phase 6 candidate handoff 可以真正写入长期记忆。
- 需要测试 Planner 被 PolicyValidator 拒绝后生成 policy learning note。
- 需要测试低置信度、低价值、单次噪音候选被拒绝。
- 需要测试重复失败合并为同一个 failure pattern，并记录 merged source refs / merge count。
- 需要测试人类正反馈提升 confidence / successContribution。
- 需要测试人类负反馈降低 confidence / successContribution 或 deactivates memory。
- 需要测试相同 usage feedback 幂等，不重复升降权。
- 需要测试敏感字段在 candidate、memory、usage、feedback audit 中都被脱敏。
- 需要测试长期记忆能被下一次 UnifiedContextBuilder 召回，并出现在 citations 中。
- 需要测试 recalled memory 的 usage record 可以被后续 usefulness feedback 更新。
- 需要测试 archived / inactive memory 不参与召回。
- 需要测试 token budget 和 stage profile 仍然限制长期记忆召回。
- 需要测试事务失败时不会留下半写入 candidate / feedback / memory scoring。
- 需要新增 V2 Phase 7 acceptance boundary guard，证明没有新增 REST Controller、Web Console、复杂权限、外部通知、自动删除 Observation、真实 LLM CI 依赖或 LLM 直接写长期记忆。
- 完成后应运行完整后端测试 `mvn test`。

## Out of Scope

- 不做完整前端 UI。
- 不做 Web Console。
- 不做 REST Controller。
- 不做复杂权限系统。
- 不做多人协同记忆审核。
- 不做企业级知识治理后台。
- 不做 Slack、邮件、Webhook、Jira、GitHub issue 或外部通知集成。
- 不做异步队列、分布式 worker、后台自治学习任务。
- 不做真实 LLM 作为 CI 必需依赖。
- 不让 LLM 直接写长期记忆。
- 不让未校验的人类输入直接进入长期记忆。
- 不自动删除原始 Observation、ExecutionRecord、HumanDecisionRecord 或 PolicyValidationResult。
- 不做完整 Agent Evaluation Harness；那是 Phase 8。
- 不重写现有 MemoryRefinery、UnifiedContextBuilder、FailureAnalysis 或 HumanInTheLoop 工作流。
- 不做 UI 层 memory usefulness feedback 表单；本阶段只做后端可测试入口。

## Further Notes

Phase 7 是 ProbeFlow V2 Agent Core 的“学习能力”阶段。

前几个阶段回答的是：

- Agent 如何调用 LLM 但不失控。
- Agent 如何知道自己有哪些工具。
- Agent 如何做受控规划。
- Agent 如何被安全策略拦住。
- Agent 如何在失败时重规划。
- Agent 如何在不确定时问人。

Phase 7 要回答：

```text
Agent 如何从这些过程里学习？
Agent 如何知道什么值得记住？
Agent 如何避免把噪音、秘密、错误建议写进长期记忆？
Agent 如何在下一次任务中真正复用经验？
```

面试讲解时可以强调：

```text
我没有把 memory 做成简单的聊天记录或向量库。
我把它设计成一个受治理的反馈闭环：
Candidate intake 负责收集经验，
MemoryRefinery 负责校验、合并和提炼，
UnifiedContextBuilder 负责召回，
Usefulness feedback 负责根据后续效果调整权重。

这样 Agent 不是自由乱学，而是在审计边界内持续改进。
```

这会让 ProbeFlow 从“能和人协作的 Agent”，进一步变成“能从协作和失败中学习的 Agent”。
