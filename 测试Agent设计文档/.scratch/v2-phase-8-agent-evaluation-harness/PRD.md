状态：ready-for-agent

# ProbeFlow V2 Phase 8：Agent Evaluation Harness PRD

## Problem Statement

ProbeFlow V2 已经完成了 Agent Core 的主要能力：LLM Provider、Tool Contract、Controlled Planner、Policy Validator、Replanning Loop、Human-in-the-loop Workflow 和 Agent Memory Feedback Loop。系统现在不仅能稳定执行 V1 的 API 测试闭环，也能让 Agent 在受控边界内规划、被策略校验、失败后恢复、等待人类输入，并把高价值经验沉淀进长期记忆。

但目前还缺最后一块：系统如何证明 Agent 设计是有效的。

现有测试主要证明各模块“能工作”：

- Planner 能生成结构化 `PlanDecision`。
- PolicyValidator 能拦截越界工具和危险动作。
- Replanning 能在失败后插入或截断步骤。
- Human-in-the-loop 能创建等待请求并消费人类决策。
- Memory Feedback 能把经验候选送入 MemoryRefinery，并在后续上下文中召回。
- 用例生成、失败分析、报告生成等 V1/V2 模块各自有应用层测试。

这些测试很重要，但它们还不能回答面试中最关键的问题：

```text
你怎么证明这个 Agent 真的比固定流水线更聪明？
你怎么证明 Planner 选对了下一步？
你怎么证明工具调用没有越权？
你怎么证明上下文引用有用，而不是堆了一堆无关 RAG？
你怎么证明失败归因、用例覆盖、报告总结和记忆复用是可评估的？
```

如果没有评估体系，Agent 能力就容易变成“我觉得它更智能”。Phase 8 要把这种主观描述变成固定样例、确定性评估、结构化指标和可重复回归命令。

Phase 8 的目标不是做学术 benchmark，也不是做商业化大屏，而是在后端建立一个 Agent Evaluation Harness，让 ProbeFlow 能用一组 golden task fixtures 持续评估 Agent 的核心能力，并输出能在面试和开发中讲清楚的 evaluation report。

## Solution

新增 Agent Evaluation Harness，作为 V2 Agent Core 的评估层。

它围绕固定 golden fixtures 运行一批确定性评估用例，覆盖 Planner、Policy、Context、Memory、FailureAnalysis、TestCaseGeneration、Report 等关键 Agent 能力，并生成结构化 Evaluation Report。

目标链路是：

```text
GoldenTaskFixture Dataset
        |
        v
AgentEvaluationApplicationService
        |
        v
Fixture setup + deterministic fake providers
        |
        v
Planner / PolicyValidator / ContextBuilder / Memory / FailureAnalysis / CaseGeneration / Report
        |
        v
Evaluator modules
        |
        v
EvaluationRun + EvaluationCaseResult + EvaluationMetricResult
        |
        v
Regression evaluation command + structured evaluation report
```

建议最高测试接缝是 `AgentEvaluationApplicationService` 或等价应用层服务。它是 Phase 8 的唯一主入口，负责加载 dataset、运行 fixture、调用现有应用层能力、收集结果、计算指标、生成报告。

Phase 8 不应该把评估逻辑散落到各个业务模块里。业务模块继续负责业务行为；Evaluation Harness 负责“用固定任务观察这些行为是否符合预期”。

核心能力包括：

- Golden task fixtures：固定样例任务、输入物料、上下文、期望 Planner 决策、期望工具策略、期望知识引用、期望记忆复用、期望失败分类、期望用例覆盖、期望报告结构。
- Planner decision accuracy evaluation：评估 Planner action、proposed step、risk level、confidence、blocker / human input 是否符合 fixture 预期。
- Tool selection validity evaluation：评估 Planner 提出的工具是否可见、白名单允许、满足前置条件、风险等级和人工确认要求是否正确。
- Context citation usefulness evaluation：评估 Unified Context 中的知识、记忆、接口结构引用是否命中预期来源，是否带 citation，是否没有明显无关上下文。
- Failure classification accuracy evaluation：评估失败分析是否把 HTTP 执行事实归类为正确的 failure reason、risk level、next suggestion 和可沉淀记忆候选。
- Test case coverage evaluation：评估生成的测试用例是否覆盖正常路径、参数校验、鉴权缺失、边界值、错误码、链路变量或 fixture 指定风险。
- Report usefulness evaluation：评估报告是否包含任务摘要、执行统计、失败摘要、关键证据、建议动作、记忆学习结果和引用来源。
- Memory reuse evaluation：评估上一轮任务沉淀的长期记忆是否在下一轮任务中被召回、被引用、被 usage 记录，并根据后续反馈调整 usefulness。
- Regression evaluation command：提供一个稳定命令或测试入口，让开发者在提交前运行 Agent 评估集。
- Evaluation report：输出结构化报告，包含 dataset version、run status、每个 case 的 pass/fail、每个 metric 的分数、失败原因、回归差异和建议修复方向。

Phase 8 必须保持确定性：

- CI 默认使用 fake LLM、fake planner scenario、fake embedding、fake HTTP gateway 或固定执行事实。
- 真实 LLM 可以作为手动实验模式，但不能成为 CI 和回归评估的必需依赖。
- 所有 golden fixture 都应该明确 expected outcome，不能靠“LLM 自己觉得对”来判分。
- 评估指标优先使用规则化、可解释 scoring，不做黑盒模型评分。

Phase 8 完成后，ProbeFlow 可以在面试中讲清楚：

```text
我不是只接了一个 LLM。
我把 Agent 拆成可控规划、工具契约、策略校验、上下文、记忆、恢复和人工反馈。
然后我用固定 golden tasks 评估 Planner 选步、工具合法性、上下文引用、失败归因、用例覆盖、报告质量和记忆复用。
这让 Agent 能力可以被回归测试，而不是靠演示运气。
```

## User Stories

1. As an Agent 系统开发者, I want an Agent Evaluation Harness, so that 我可以证明 Agent Core 的能力不是主观感觉。
2. As an Agent 系统开发者, I want a single evaluation application service, so that 所有 Agent 评估都通过一个稳定入口运行。
3. As an Agent 系统开发者, I want evaluation datasets, so that 不同阶段的评估用例可以按版本管理。
4. As an Agent 系统开发者, I want golden task fixtures, so that 每个评估任务都有固定输入和固定预期。
5. As an Agent 系统开发者, I want fixture versioning, so that 指标变化时可以知道是代码变化还是样例变化。
6. As an Agent 系统开发者, I want fixture metadata, so that 每个样例可以标记覆盖的 Agent 能力。
7. As an Agent 系统开发者, I want fixture setup to seed task state, so that 评估可以模拟真实 Task 流程。
8. As an Agent 系统开发者, I want fixture setup to seed ApiSpec, so that Planner 和生成模块能看到真实接口结构。
9. As an Agent 系统开发者, I want fixture setup to seed KnowledgeDocument and KnowledgeChunk, so that RAG 引用可以被评估。
10. As an Agent 系统开发者, I want fixture setup to seed LongTermMemory, so that memory reuse 可以被评估。
11. As an Agent 系统开发者, I want fixture setup to seed ExecutionRecord, so that failure analysis 可以被评估。
12. As an Agent 系统开发者, I want fixture setup to seed HumanDecisionRecord when needed, so that HITL 反馈链路可以被评估。
13. As an Agent 系统开发者, I want deterministic fake providers, so that CI 不依赖真实 LLM、真实 embedding 或真实 HTTP 服务。
14. As an Agent 系统开发者, I want real-provider evaluation disabled by default, so that 回归评估稳定可重复。
15. As an Agent 系统开发者, I want a manual real-provider mode later, so that 可以单独观察真实 LLM 表现但不影响 CI。
16. As an Agent 系统开发者, I want planner decision accuracy scoring, so that 可以知道 Planner action 是否选对。
17. As an Agent 系统开发者, I want planner proposed step scoring, so that 可以知道 Planner 插入的步骤是否符合预期。
18. As an Agent 系统开发者, I want planner stop/continue/replan/wait scoring, so that 不同规划动作都可以被评估。
19. As an Agent 系统开发者, I want planner blocker scoring, so that Planner 在信息不足时能提出正确 blocker。
20. As an Agent 系统开发者, I want planner human input scoring, so that WAIT_FOR_HUMAN 的 required input schema 可以被评估。
21. As an Agent 系统开发者, I want planner confidence band scoring, so that 过度自信和过度保守都能被发现。
22. As an Agent 系统开发者, I want planner risk level scoring, so that 高风险动作不会被低风险标记。
23. As an Agent 系统开发者, I want tool selection validity scoring, so that Planner 不能选择不可见工具。
24. As an Agent 系统开发者, I want tool whitelist scoring, so that Planner 不能绕过 AgentPolicy。
25. As an Agent 系统开发者, I want tool precondition scoring, so that Planner 不能在条件不足时执行工具。
26. As an Agent 系统开发者, I want tool human-confirmation scoring, so that 高风险工具必须进入人工确认。
27. As an Agent 系统开发者, I want V1 boundary policy scoring, so that UI 自动化、Service 直调、DB 直连断言仍会被拦截。
28. As an Agent 系统开发者, I want policy rejection reason scoring, so that 被拒绝的决策有正确可解释原因。
29. As an Agent 系统开发者, I want context citation usefulness scoring, so that RAG 和 Memory 不是无脑堆上下文。
30. As an Agent 系统开发者, I want expected citation matching, so that fixture 可以声明必须命中的知识片段或记忆。
31. As an Agent 系统开发者, I want citation source type scoring, so that knowledge、memory、api context 可以分别评估。
32. As an Agent 系统开发者, I want irrelevant context penalty, so that 无关 chunk 或过时 memory 不会拿高分。
33. As an Agent 系统开发者, I want citation token budget scoring, so that 上下文质量不会靠无限塞 token 获得。
34. As an Agent 系统开发者, I want context coverage scoring, so that 缺接口结构、缺业务文档、缺长期记忆都能被发现。
35. As an Agent 系统开发者, I want failure classification accuracy scoring, so that 失败归因可以被量化。
36. As an Agent 系统开发者, I want failure reason matching, so that 鉴权失败、参数校验失败、环境缺失、服务异常不会混淆。
37. As an Agent 系统开发者, I want failure risk scoring, so that 高风险失败不会被低估。
38. As an Agent 系统开发者, I want next suggestion scoring, so that 失败分析输出的下一步动作符合预期。
39. As an Agent 系统开发者, I want memory candidate scoring from failure analysis, so that 高价值失败能进入学习候选。
40. As an Agent 系统开发者, I want test case coverage scoring, so that 用例生成质量不只看数量。
41. As an Agent 系统开发者, I want happy path coverage scoring, so that 正常请求场景必须存在。
42. As an Agent 系统开发者, I want validation coverage scoring, so that 必填字段、格式、范围等约束有负向用例。
43. As an Agent 系统开发者, I want auth coverage scoring, so that token 缺失、权限不足等鉴权场景可被检查。
44. As an Agent 系统开发者, I want boundary value coverage scoring, so that 数值、长度、枚举边界可被检查。
45. As an Agent 系统开发者, I want business rule coverage scoring, so that RAG 中的业务规则能反映到用例。
46. As an Agent 系统开发者, I want suite dependency coverage scoring when fixture needs it, so that 链路变量提取和引用可以被评估。
47. As an Agent 系统开发者, I want duplicate case penalty, so that 生成一堆重复用例不会拿高分。
48. As an Agent 系统开发者, I want report usefulness scoring, so that 报告不是简单日志拼接。
49. As an Agent 系统开发者, I want report summary scoring, so that 报告能说明任务总体结果。
50. As an Agent 系统开发者, I want report evidence scoring, so that 失败结论有 execution、observation、citation 证据。
51. As an Agent 系统开发者, I want report recommendation scoring, so that 报告能给出下一步修复建议。
52. As an Agent 系统开发者, I want report memory learning summary scoring, so that 报告能展示 Agent 学到了什么。
53. As an Agent 系统开发者, I want report no-secret scoring, so that 报告不会泄露 authorization、cookie、password 或 token。
54. As an Agent 系统开发者, I want memory reuse scoring, so that 长期记忆不是只写不用。
55. As an Agent 系统开发者, I want memory recall scoring, so that 预期记忆能被下一轮 Unified Context 召回。
56. As an Agent 系统开发者, I want memory citation scoring, so that Planner 或生成流程能引用被召回的记忆。
57. As an Agent 系统开发者, I want memory usage record scoring, so that 每次使用长期记忆都有审计记录。
58. As an Agent 系统开发者, I want memory usefulness feedback scoring, so that 记忆是否有帮助能反向更新评分。
59. As an Agent 系统开发者, I want memory merge scoring, so that 重复失败会合并成更强的 pattern。
60. As an Agent 系统开发者, I want negative memory feedback scoring, so that 被证明误导的记忆会降权。
61. As an Agent 系统开发者, I want evaluation case results, so that 每个 fixture 的通过和失败原因都能被定位。
62. As an Agent 系统开发者, I want evaluation metric results, so that 每类能力有独立分数。
63. As an Agent 系统开发者, I want dataset-level summary, so that 可以快速看到整体 Agent 能力健康度。
64. As an Agent 系统开发者, I want pass/fail thresholds, so that 回归评估可以阻止明显退化。
65. As an Agent 系统开发者, I want weighted metrics, so that Planner、Policy、Memory 等核心能力可以比展示类指标更重要。
66. As an Agent 系统开发者, I want actionable failure messages, so that 评估失败后知道应该修哪个模块。
67. As an Agent 系统开发者, I want structured evaluation report, so that 结果可以被测试、日志或后续 UI 消费。
68. As an Agent 系统开发者, I want human-readable evaluation report text, so that 面试和调试时能直接讲清楚结果。
69. As an Agent 系统开发者, I want regression evaluation command, so that 提交前可以一条命令跑 Agent 能力回归。
70. As an Agent 系统开发者, I want evaluation to run inside Maven tests, so that 团队协作时不用记额外工具链。
71. As an Agent 系统开发者, I want evaluation not to mutate production-like data, so that 评估样例不会污染正常任务。
72. As an Agent 系统开发者, I want evaluation cleanup or isolated fixture namespace, so that 多次运行不会互相影响。
73. As an Agent 系统开发者, I want idempotent evaluation run creation, so that 重试不会产生重复脏数据。
74. As an Agent 系统开发者, I want evaluation audit metadata, so that 可以知道本次 run 使用的 dataset、provider、profile 和时间。
75. As an Agent 系统开发者, I want evaluation to keep existing module ownership, so that 评估层不会重写 Planner、Policy、Memory 或 Report。
76. As an Agent 系统开发者, I want acceptance boundary guard tests, so that Phase 8 不会偷偷变成 UI、大屏、A/B 平台或外部 benchmark。
77. As an Agent 系统开发者, I want no external SaaS dependency in evaluation, so that 本地和 CI 都能跑。
78. As an Agent 系统开发者, I want no AutoGPT-style free loop in evaluation, so that 评估对象仍然是受控 Agent Core。
79. As an Agent 系统开发者, I want evaluation examples to match interview narrative, so that 项目可以清楚展示工程化 Agent 的设计价值。
80. As an Agent 系统开发者, I want Phase 8 to complete V2 Agent Core, so that V2 可以形成“可控、可恢复、可学习、可评估”的闭环。

## Implementation Decisions

- 新增 Agent Evaluation Harness 后端模块，建议入口为 `AgentEvaluationApplicationService` 或等价应用层服务。
- Agent Evaluation Harness 只做评估编排，不接管业务执行；Planner、PolicyValidator、UnifiedContextBuilder、MemoryRefinery、FailureAnalysis、TestCaseGeneration、ReportGeneration 等模块继续保持原有职责。
- 引入 `EvaluationDataset` 或等价概念，用于描述一组 golden task fixtures、dataset version、适用能力标签、默认阈值和权重。
- 引入 `GoldenTaskFixture` 或等价概念，用于描述单个评估样例的输入、前置数据、预期行为和 expected metrics。
- 引入 `EvaluationRun` 或等价结果对象，记录 run id、dataset name、dataset version、profile、provider mode、startedAt、completedAt、status、overall score 和 summary。
- 引入 `EvaluationCaseResult` 或等价结果对象，记录每个 fixture 的执行状态、能力标签、实际结果、预期结果、失败原因和 metric results。
- 引入 `EvaluationMetricResult` 或等价结果对象，记录 metric name、score、passed、threshold、weight、actual、expected 和 diagnostic message。
- 引入 `EvaluationReport` 或等价结构化报告，聚合 run summary、case summary、metric summary、regression summary 和 recommended fixes。
- Golden fixture 应支持以下预期字段或等价表达：expected planner action、expected tool name、expected policy status、expected citations、expected failure classification、expected test case coverage、expected report sections、expected memory reuse。
- Planner decision accuracy 评估应关注 action、proposed step、proposed tool、confidence band、risk level、blockers、required human input，而不是 prompt 字符串。
- Tool selection validity 评估应复用 ToolContractRegistry、AgentPolicy 和 PolicyValidator 的真实逻辑，而不是在评估层重新实现一套策略。
- Context citation usefulness 评估应消费 UnifiedContextBuilder 输出的 context、coverage、budget 和 citations，判断预期引用是否出现、无关引用是否过多、token budget 是否被遵守。
- Failure classification accuracy 评估应消费 FailureAnalysis 的结构化结果，判断 failure reason、risk level、next suggestion 和 memory candidate 是否符合 fixture 预期。
- Test case coverage 评估应消费生成后的 TestCaseDraft 或 TestCase 结构，判断 scenario、assertion、request variation、auth negative case、business rule case 和 duplicate ratio。
- Report usefulness 评估应消费报告生成结果，判断摘要、统计、失败证据、建议动作、引用来源和学习结果是否完整。
- Memory reuse 评估应覆盖至少两段式 fixture：先制造可学习经验，再运行后续任务，验证长期记忆被召回、被引用、被 usage 记录、被 usefulness feedback 调整。
- Evaluation Harness 应支持 dataset-level 和 case-level 阈值。核心指标低于阈值时，EvaluationRun 应失败。
- Metric 权重应可配置或在 dataset 内声明。Planner、Policy、Context、Memory 等 Agent 核心能力权重应高于展示类指标。
- Evaluation Harness 可以使用应用内持久化记录，也可以先以应用层结果对象返回；但结果必须是结构化的，不能只输出日志。
- 如果新增持久化表，应为 evaluation run、case result、metric result 提供迁移，并保持 H2 test profile 可迁移。
- Golden fixtures 可以放在测试资源、代码内 fixture builder 或本地配置中；首版优先选择最容易被 Maven 测试稳定加载的方式。
- Fixture setup 应使用隔离 namespace、唯一 run id 或 transaction cleanup，避免污染正常任务数据。
- Fixture setup 应可重复运行；同一 dataset 多次执行不应因重复 id 或残留数据失败。
- CI 默认 provider mode 必须是 fake / deterministic；真实 LLM、真实 embedding 和真实外部 HTTP 只能作为手动实验模式。
- Evaluation Harness 不直接依赖外部 SaaS、商业 benchmark、dashboard、队列或分布式 worker。
- Regression evaluation command 优先通过 Maven 测试入口表达，例如 dedicated evaluation test suite；可以补充 Spring profile 或命令入口，但不要引入新的运行时工具链。
- Evaluation Report 应同时支持机器可断言结构和人类可读摘要。
- Evaluation Report 的失败信息必须指向能力维度和可能模块，例如 planner-decision、policy-validation、context-citation、memory-reuse，而不是只给总分。
- Evaluation Harness 必须脱敏所有报告和诊断中的敏感字段，包括 authorization、cookie、password、secret、token 等。
- Evaluation Harness 不应为了通过评估修改业务模块行为；评估失败应暴露真实退化，而不是在评估层特殊放行。
- Phase 8 不做 REST Controller、Web Console、商业化大屏、复杂 A/B 平台、学术 benchmark 或外部平台集成。

## Testing Decisions

- 最高测试接缝是 `AgentEvaluationApplicationService` 或等价应用层服务；优先从这个入口运行 dataset 并断言 EvaluationRun、EvaluationCaseResult 和 EvaluationMetricResult。
- 测试应该断言外部行为：case 是否通过、metric 分数、阈值判断、诊断信息、报告结构、fixture 隔离、provider mode 和敏感信息脱敏。
- 测试不要断言私有 helper、内部排序实现、prompt 文案、日志文本或具体字符串拼接细节。
- 需要新增 Planner decision accuracy 评估测试，覆盖 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP 和 failed/blocked decision。
- 需要新增 Tool selection validity 评估测试，覆盖 allowed、blocked、requires human confirmation、unknown tool、not visible、missing precondition 和 V1 boundary blocked。
- 需要新增 Context citation usefulness 评估测试，覆盖 expected knowledge citation、expected memory citation、irrelevant citation penalty、token budget 和 low confidence 标记。
- 需要新增 Failure classification accuracy 评估测试，覆盖鉴权失败、参数校验失败、环境缺失、服务异常和高价值 memory candidate。
- 需要新增 Test case coverage 评估测试，覆盖 happy path、validation negative case、auth negative case、boundary value、business rule 和 duplicate penalty。
- 需要新增 Report usefulness 评估测试，覆盖任务摘要、执行统计、失败证据、建议动作、引用来源、记忆学习摘要和 no-secret 输出。
- 需要新增 Memory reuse 评估测试，证明上一轮生成的长期记忆能在下一轮 context 中被召回、被 usage 记录，并能接受 usefulness feedback。
- 需要新增 dataset threshold 测试，证明核心 metric 低于阈值时 EvaluationRun 失败。
- 需要新增 metric weighting 测试，证明 overall score 按权重汇总。
- 需要新增 fixture isolation 测试，证明多次运行同一 dataset 不会互相污染。
- 需要新增 deterministic provider 测试，证明默认评估不会调用真实 LLM、真实 embedding 或真实 HTTP。
- 需要新增 report sanitization 测试，证明 evaluation report、case diagnostic 和 metric detail 不泄露 token、authorization、cookie、password 或 secret。
- 需要新增 regression command 覆盖，至少通过 dedicated Maven test suite 证明 Agent 评估集可以一条命令运行。
- 需要新增 V2 Phase 8 acceptance boundary guard，证明没有新增 REST Controller、Web Console、商业化大屏、复杂 A/B 平台、学术 benchmark、外部 SaaS、队列 worker、分布式 worker 或真实 LLM CI 依赖。
- 现有 V2 Phase 1 到 Phase 7 的应用层测试是重要先例；Phase 8 应复用现有 fake provider、fake planner scenario、fake HTTP gateway、MemoryRefinery、UnifiedContextBuilder、PolicyValidator 和 ReportGeneration 测试方式。
- 完成后应运行完整后端测试 `mvn test`。

## Out of Scope

- 不做完整前端 UI。
- 不做 Web Console。
- 不做商业化评估大屏。
- 不做复杂 A/B 平台。
- 不做学术 benchmark。
- 不接入外部评测平台。
- 不接入 OpenAI Evals、LangSmith、Weights & Biases、DeepEval、Ragas 或类似外部依赖作为必需链路。
- 不让真实 LLM 成为 CI 或默认回归评估依赖。
- 不做多模型横向排行榜。
- 不做生产流量在线评分。
- 不做长期趋势分析和团队质量看板。
- 不做队列 worker、分布式评估任务、后台定时评估。
- 不做 REST Controller、Webhook、Slack、Jira、GitHub issue 或外部通知集成。
- 不把 Evaluation Harness 改造成另一个 Agent。
- 不让 Evaluation Harness 绕过 PolicyValidator 或 ToolContract。
- 不重写 Planner、PolicyValidator、UnifiedContextBuilder、MemoryRefinery、FailureAnalysis、TestCaseGeneration 或 ReportGeneration。
- 不为了评估通过而硬编码业务模块输出。
- 不把 Phase 8 扩展成 V3 产品化入口。

## Further Notes

Phase 8 是 V2 Agent Core 的收束阶段。

前面阶段已经回答了这些问题：

- Agent 如何安全接入 LLM。
- Agent 如何知道自己有哪些工具。
- Agent 如何做受控规划。
- Agent 如何被策略校验拦住。
- Agent 如何在失败后重规划。
- Agent 如何等待人类输入并恢复。
- Agent 如何把失败和人类反馈沉淀成长期记忆。

Phase 8 要回答最后一个问题：

```text
Agent 如何证明自己有效？
```

这个阶段完成后，ProbeFlow V2 的面试叙事会非常完整：

```text
V1 是确定性 API 测试闭环。
V2 是工程化 Agent Core。
Agent 不直接乱调工具，而是通过 Planner -> PolicyValidator -> ToolRouter -> ApplicationService。
每一步都有 StepOutcome，每个长期结论进入 Observation / Memory / Report。
失败可以恢复，人类可以介入，经验可以沉淀。
最后用 Agent Evaluation Harness 做 golden task 回归，证明规划、工具、上下文、失败分析、用例覆盖、报告和记忆复用都可评估。
```

Phase 8 不追求“评测平台很炫”，而是追求一个面试中能讲得明白、代码中能跑得稳定、团队协作中能持续防回归的后端评估体系。
