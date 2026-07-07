状态：ready-for-agent

# ProbeFlow V3：智能链路测试 Agent PRD

## Problem Statement

ProbeFlow 已经完成了 V1 确定性 API 测试后端闭环，也完成了 V2 Agent Core：LLM Provider、Tool Contract、Controlled Planner、Policy Validator、Replanning Loop、Human-in-the-loop、Memory Feedback 和 Agent Evaluation Harness。

现在系统已经可以被称为一个受控的 API 测试 Agent，但它在“业务链路测试”上仍然只是基础版：

- `SUITE` 已经存在，但更多是用户显式选择多个接口后生成和执行一条顺序链路。
- SUITE 执行已经支持 step 顺序、失败后跳过后续步骤、suite 失败分析和报告呈现。
- 请求模板已经支持变量占位符解析，但变量主要来自执行请求里的环境变量和鉴权变量。
- 系统还没有完整的 `ExecutionContext`、多级变量作用域、响应提取、变量回写和下游步骤自动引用。
- 系统还不能从 ApiSpec、Knowledge RAG、Memory 和接口字段关系里自动推断业务链路。
- 系统还不能自动判断“哪个接口产出变量、哪个接口消费变量”，也不能生成成对的 `extractRules` 和后续步骤变量引用。
- 失败分析能识别 suite 前置步骤失败，但还不能细分变量提取失败、变量注入失败、链路依赖缺口、业务前置条件缺失和真实下游接口失败。

这会导致 ProbeFlow 在面试中讲 Agent 设计时还缺一个很直观的亮点。

单接口测试可以证明系统能分析接口、生成用例、执行和总结；但真正体现 Agent 价值的是：

```text
Agent 能不能理解业务流程？
Agent 能不能把多个接口组成可执行链路？
Agent 能不能维护步骤间的数据流？
Agent 能不能知道失败是前置依赖失败、变量缺失，还是下游接口真的有问题？
Agent 能不能把这类链路经验反馈给 Memory？
```

V3 要解决这个问题。V3 的目标不是做 Web 控制台，也不是补完整版 RAG/Memory，而是把现有基础版 SUITE 升级为“智能链路测试 Agent”：

```text
从接口资产、文档知识、历史经验和字段关系中推断业务链路，
生成带变量提取和变量注入的 SUITE，
用 ExecutionContext 稳定执行，
把链路失败原因结构化分析并反馈给记忆系统。
```

完成 V3 后，ProbeFlow 在面试中可以讲清楚：

```text
我不是只做了单接口自动化。
我做了一个 API 测试 Agent，它能理解业务链路、规划步骤、生成链路用例、维护执行上下文、处理前置失败，并把链路经验沉淀为长期记忆。
```

## Solution

新增 V3 智能链路测试 Agent 能力，围绕 `SUITE` 做一次完整升级。

V3 的核心链路是：

```text
ApiSpec 集合
+ Knowledge RAG business_flow / api_note / test_spec
+ LongTermMemory failure_pattern / testing_pattern / preference
+ 字段与路径语义线索
        |
        v
Business Flow Discovery
        |
        v
Dependency Linker
        |
        v
Suite Case Generation
        |
        v
Execution Context + Variable Resolver
        |
        v
Step Execution + Response Extractor + Variable Write Back
        |
        v
Suite Failure Analysis
        |
        v
Observation / Memory Candidate / Report / Agent Evaluation
```

V3 要把 SUITE 从“多个接口按顺序执行”升级成“带数据流、依赖关系和失败归因的业务链路测试”。

核心能力包括：

- 业务链路发现：从用户选择的 ApiSpec、业务流程文档、接口命名、HTTP 方法、路径语义、DTO 字段、RAG 上下文和长期记忆中推断候选链路。
- 链路步骤排序：判断步骤顺序，例如登录、创建订单、支付、查询订单、取消订单。
- 依赖关系推导：识别生产者和消费者，例如创建订单响应里的 `orderId` 被支付接口请求使用。
- `extractRules` 生成：为生产变量的 step 生成响应提取规则。
- 变量引用生成：为消费变量的 step 在 request template 中写入 `${suite.orderId}` 或 `${step.createOrder.orderId}` 这类引用。
- ExecutionContext：引入 env、task、suite、case、step、fn、data 等作用域，统一管理链路执行时变量。
- VariableResolver：在请求执行前解析 `${scope.path}` 表达式，支持对象路径和简单数组下标。
- DynamicValueProvider：支持基础动态值，例如 uuid、now、randomEmail、randomPhone、randomFrom。
- ResponseExtractor：从 JSON body、header、statusCode 中提取变量，支持 required 和 failureStrategy。
- VariableWriteBackService：把提取结果写回 ExecutionContext，并记录变量审计。
- Suite 执行策略：支持 fail fast、continue on failure、continue if non critical。
- 链路失败分类：区分前置步骤失败、变量提取失败、变量缺失、变量注入失败、断言失败、业务状态不满足、下游接口真实失败。
- Memory Feedback：把高价值链路失败、变量依赖经验、业务前置条件经验写入 Memory 候选。
- Agent Evaluation：扩展现有评估集，验证 suite dependency coverage、变量提取、变量注入、链路失败归因和记忆复用。
- V3 Light Console：提供一个面向本地手动测试和面试演示的轻量前端，用来启动 V3 链路任务、切换 fake / manual real LLM、查看 SUITE 草稿、变量审计、执行结果、失败分析、记忆反馈和报告摘要。

V3 的最高测试接缝是应用层。

生成侧优先复用现有 `SUITE` 测试用例生成入口，让外部行为保持为：

```text
给定 Task + 多个 ApiSpec + RAG / Memory 上下文
生成一个可解释、可执行、带 extractRules 和变量引用的 SUITE 草稿
```

执行侧优先复用现有 SUITE HTTP 执行入口，让外部行为保持为：

```text
给定 SUITE TestCase + 环境变量 + 鉴权变量
按步骤执行，维护 ExecutionContext，输出每步详情、变量审计、失败分类和执行记录
```

如果实现过程中发现跨发现、生成、执行、分析需要一个统一入口，可以新增一个很薄的 V3 链路工作流应用服务。它只负责串联已有应用层服务，不重新实现 TestCaseGeneration、HttpExecution、FailureAnalysis、Memory 或 Report。

## User Stories

1. As an API 测试 Agent 使用者, I want Agent 自动识别业务链路, so that 我不用手工把多个接口拼成链路测试。
2. As an API 测试 Agent 使用者, I want Agent 根据业务文档识别链路顺序, so that 文档里的真实业务流程可以进入测试资产。
3. As an API 测试 Agent 使用者, I want Agent 根据接口路径识别链路顺序, so that `/login`、`/orders`、`/payments`、`/orders/{id}` 这类接口可以形成自然流程。
4. As an API 测试 Agent 使用者, I want Agent 根据 HTTP 方法识别操作阶段, so that `POST` 创建、`GET` 查询、`PATCH` 更新、`DELETE` 删除可以辅助排序。
5. As an API 测试 Agent 使用者, I want Agent 根据 DTO 字段关系推断依赖, so that 上游响应字段可以自动传给下游请求字段。
6. As an API 测试 Agent 使用者, I want Agent 根据 RAG 中的 business_flow 文档推断链路, so that 人类写好的流程说明可以提升链路准确性。
7. As an API 测试 Agent 使用者, I want Agent 根据长期记忆中的历史经验推断链路风险, so that 过去踩过的前置条件可以被自动考虑。
8. As an API 测试 Agent 使用者, I want Agent 给出候选链路和置信度, so that 我能知道链路是否可信。
9. As an API 测试 Agent 使用者, I want Agent 给出链路来源证据, so that 我能知道链路来自文档、接口命名、字段关系还是历史经验。
10. As an API 测试 Agent 使用者, I want Agent 在链路证据不足时降级为人工确认, so that 系统不会自信地生成错误流程。
11. As an API 测试 Agent 使用者, I want Agent 支持用户显式选择接口和顺序, so that 我仍然可以手工控制关键链路。
12. As an API 测试 Agent 使用者, I want Agent 支持用户显式选择接口但自动补充依赖, so that 我只需要选目标接口，系统帮我补前置步骤。
13. As an API 测试 Agent 使用者, I want Agent 支持多个候选链路, so that 一个模块里的不同业务路径都可以覆盖。
14. As an API 测试 Agent 使用者, I want Agent 标记主链路和备选链路, so that 回归时可以优先跑最重要路径。
15. As an API 测试 Agent 使用者, I want Agent 为每个链路步骤生成稳定 stepId, so that 变量、失败和报告都能引用同一个步骤。
16. As an API 测试 Agent 使用者, I want Agent 为每个链路步骤生成 stepName, so that 报告和失败分析容易读懂。
17. As an API 测试 Agent 使用者, I want Agent 为每个链路步骤生成 order, so that SUITE 执行顺序稳定。
18. As an API 测试 Agent 使用者, I want Agent 为关键步骤标记 critical, so that 前置关键步骤失败时可以正确停止后续步骤。
19. As an API 测试 Agent 使用者, I want Agent 为非关键步骤标记 non-critical, so that 非关键辅助步骤失败时可以继续主流程。
20. As an API 测试 Agent 使用者, I want Agent 生成链路级 scenarioName, so that SUITE 测试资产能体现业务意图。
21. As an API 测试 Agent 使用者, I want Agent 生成链路级 tags, so that 链路用例可以按模块、业务、风险和来源筛选。
22. As an API 测试 Agent 使用者, I want Agent 生成 basedOnApiSpecIds, so that SUITE 能追踪它基于哪些接口。
23. As an API 测试 Agent 使用者, I want Agent 生成 generatedFromSingleCaseIds, so that SUITE 可以说明它复用了哪些单接口用例。
24. As an API 测试 Agent 使用者, I want Agent 生成 basedOnKnowledgeRefs, so that SUITE 可以说明它引用了哪些业务流程文档。
25. As an API 测试 Agent 使用者, I want Agent 生成 basedOnMemoryRefs, so that SUITE 可以说明它引用了哪些历史经验。
26. As an API 测试 Agent 使用者, I want Agent 识别上游响应变量, so that 创建类接口返回的 id、token、status 等可以被后续步骤使用。
27. As an API 测试 Agent 使用者, I want Agent 识别下游请求变量, so that 支付、查询、取消等接口可以自动引用上游产物。
28. As an API 测试 Agent 使用者, I want Agent 生成 extractRules, so that 每个生产变量的步骤知道从响应哪里提取数据。
29. As an API 测试 Agent 使用者, I want Agent 生成 JSON body 提取规则, so that `$.data.orderId` 这类字段可以写入上下文。
30. As an API 测试 Agent 使用者, I want Agent 生成 header 提取规则, so that token、traceId、location 等 header 可以写入上下文。
31. As an API 测试 Agent 使用者, I want Agent 生成 statusCode 提取规则, so that 特殊状态码也可以作为链路判断输入。
32. As an API 测试 Agent 使用者, I want extractRules 支持 required, so that 必须提取的变量缺失时可以阻止错误链路继续执行。
33. As an API 测试 Agent 使用者, I want extractRules 支持 failureStrategy, so that 非关键变量缺失时可以选择 fail fast、write null 或 write default。
34. As an API 测试 Agent 使用者, I want extractRules 支持 targetScope, so that 提取的变量可以写入 suite、step 或 case 作用域。
35. As an API 测试 Agent 使用者, I want extractRules 支持 targetKey, so that 提取变量有稳定名称。
36. As an API 测试 Agent 使用者, I want Agent 同时生成变量提取和变量消费引用, so that 生产者和消费者不会脱节。
37. As an API 测试 Agent 使用者, I want Agent 在后续 request template 中注入 `${suite.orderId}`, so that 下游请求可以自动使用上游结果。
38. As an API 测试 Agent 使用者, I want Agent 支持 `${step.createOrder.orderId}`, so that 变量来源可以更精确。
39. As an API 测试 Agent 使用者, I want Agent 支持 `${env.baseUrl}`, so that 不同环境可以复用同一条链路用例。
40. As an API 测试 Agent 使用者, I want Agent 支持 `${task.xxx}`, so that 任务级输入可以进入链路执行。
41. As an API 测试 Agent 使用者, I want Agent 支持 `${case.xxx}`, so that 用例级静态数据可以被步骤复用。
42. As an API 测试 Agent 使用者, I want Agent 支持 `${fn.uuid()}`, so that 测试数据可以在运行时生成。
43. As an API 测试 Agent 使用者, I want Agent 支持 `${data.randomEmail()}`, so that 常见测试数据不需要手写。
44. As an API 测试 Agent 使用者, I want 变量解析失败时得到清晰错误, so that 我知道缺哪个变量、哪个步骤引用了它。
45. As an API 测试 Agent 使用者, I want 变量提取失败时得到清晰错误, so that 我知道响应结构和预期提取规则哪里不一致。
46. As an API 测试 Agent 使用者, I want 变量写回有审计记录, so that 我可以追踪每个变量是谁写入、何时写入、来自哪个响应。
47. As an API 测试 Agent 使用者, I want 变量覆盖有审计记录, so that 同名变量被覆盖时能定位原因。
48. As an API 测试 Agent 使用者, I want ExecutionContext 在每个步骤前后都有快照, so that 链路失败时可以复盘上下文变化。
49. As an API 测试 Agent 使用者, I want ExecutionContext 隐藏敏感值, so that token、cookie、password 不会泄露到日志和报告。
50. As an API 测试 Agent 使用者, I want SUITE 执行前校验变量引用, so that 明显缺失的变量不会等到 HTTP 执行时才失败。
51. As an API 测试 Agent 使用者, I want SUITE 执行前校验 extractRules, so that 无效 JSONPath 或缺少 targetKey 的规则会提前暴露。
52. As an API 测试 Agent 使用者, I want SUITE 执行前校验步骤依赖, so that 消费变量的步骤不会排在生产变量的步骤之前。
53. As an API 测试 Agent 使用者, I want SUITE 支持 FAIL_FAST, so that 关键前置失败时后续步骤不会产生噪声失败。
54. As an API 测试 Agent 使用者, I want SUITE 支持 CONTINUE_ON_FAILURE, so that 我可以观察链路中多个接口的独立失败。
55. As an API 测试 Agent 使用者, I want SUITE 支持 CONTINUE_IF_NON_CRITICAL, so that 非关键步骤失败不影响主流程。
56. As an API 测试 Agent 使用者, I want 每个 step 生成独立执行详情, so that 报告可以展示每一步请求、响应、断言和变量变化。
57. As an API 测试 Agent 使用者, I want 每个 step 失败时记录 failureType, so that 失败分析可以精确分类。
58. As an API 测试 Agent 使用者, I want 下游步骤被跳过时记录 skipReason, so that 我不会误以为下游接口独立失败。
59. As an API 测试 Agent 使用者, I want SUITE 结果包含 firstFailingStep, so that 我能优先定位根因。
60. As an API 测试 Agent 使用者, I want SUITE 结果包含 dependentSkippedSteps, so that 我能看到受前置失败影响的范围。
61. As an API 测试 Agent 使用者, I want SUITE 结果包含 variableAuditSummary, so that 我能看到链路变量是否按预期传递。
62. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 PREREQUISITE_STEP_FAILURE, so that 前置步骤失败不会被误判为下游问题。
63. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 VARIABLE_EXTRACTION_FAILURE, so that 响应字段缺失能被单独归因。
64. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 VARIABLE_RESOLUTION_FAILURE, so that 请求模板变量缺失能被单独归因。
65. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 DEPENDENCY_ORDER_FAILURE, so that 链路顺序错误能被单独归因。
66. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 BUSINESS_PRECONDITION_FAILURE, so that 业务状态不满足能被单独归因。
67. As an API 测试 Agent 使用者, I want SUITE 失败分析识别 DOWNSTREAM_API_FAILURE, so that 真正的下游接口问题不会被前置失败掩盖。
68. As an API 测试 Agent 使用者, I want SUITE 失败分析给出 nextSuggestion, so that 我知道下一步应该修规则、补变量、改链路还是查接口。
69. As an API 测试 Agent 使用者, I want SUITE 失败分析生成 Observation, so that 链路失败可以进入报告和记忆系统。
70. As an API 测试 Agent 使用者, I want 高价值链路失败进入 Memory Candidate, so that 后续任务能记住类似业务前置条件。
71. As an API 测试 Agent 使用者, I want 变量依赖错误进入 Memory Candidate, so that 后续生成 SUITE 时避免重复错误。
72. As an API 测试 Agent 使用者, I want 用户修正 extractRules 后进入 Memory Feedback, so that Agent 能学习团队真实字段命名习惯。
73. As an API 测试 Agent 使用者, I want 用户修正链路顺序后进入 Memory Feedback, so that Agent 能学习真实业务流程。
74. As an API 测试 Agent 使用者, I want 报告展示链路图摘要, so that 我能快速理解 SUITE 是怎么跑的。
75. As an API 测试 Agent 使用者, I want 报告展示变量传递摘要, so that 我能看到关键变量从哪里来到哪里去。
76. As an API 测试 Agent 使用者, I want 报告展示链路失败根因, so that 我不用逐条日志排查。
77. As an API 测试 Agent 使用者, I want 报告展示 RAG 和 Memory 引用, so that 我能知道 Agent 为什么生成这条链路。
78. As an API 测试 Agent 使用者, I want 报告展示哪些经验被沉淀, so that 我能看到 Agent 学到了什么。
79. As an API 测试 Agent 使用者, I want 旧 SUITE 资产保持快照冻结, so that 已验证链路不会因为底层 SINGLE case 更新而偷偷变化。
80. As an API 测试 Agent 使用者, I want SUITE 支持 stale 检测, so that 底层 ApiSpec 变化时能提示我刷新链路。
81. As an API 测试 Agent 使用者, I want 重新生成 SUITE 时保留旧版本, so that 我可以比较新旧链路差异。
82. As an API 测试 Agent 使用者, I want 半自动模式下可以 review SUITE 草稿, so that 高风险链路不会直接执行。
83. As an API 测试 Agent 使用者, I want 半自动模式下可以编辑 extractRules, so that 自动推断不准时我可以修正。
84. As an API 测试 Agent 使用者, I want 半自动模式下可以编辑变量引用, so that 我可以控制下游请求如何消费变量。
85. As an API 测试 Agent 使用者, I want 半自动模式下可以编辑 critical 标记, so that 失败策略符合业务预期。
86. As an API 测试 Agent 使用者, I want 全自动模式下只执行高置信度链路, so that CI 回归稳定可靠。
87. As an API 测试 Agent 使用者, I want 低置信度链路进入 WAITING_FOR_REVIEW, so that Agent 不会在信息不足时乱跑。
88. As an API 测试 Agent 使用者, I want Controlled Planner 能感知链路 readiness, so that 变量和依赖未准备好时不会进入执行。
89. As an API 测试 Agent 使用者, I want Policy Validator 校验链路动作, so that Agent 不能绕过安全策略直接执行高风险 SUITE。
90. As an API 测试 Agent 使用者, I want Replanning Loop 能处理链路失败, so that 变量缺失或前置失败后可以插入恢复步骤或等待人工输入。
91. As an API 测试 Agent 使用者, I want Human-in-the-loop 能请求补充变量, so that 缺少 token、baseUrl 或测试数据时可以继续任务。
92. As an API 测试 Agent 使用者, I want Agent Evaluation 覆盖链路变量传递, so that V3 能力不是只靠手工演示。
93. As an API 测试 Agent 使用者, I want Agent Evaluation 覆盖 extractRules 正确性, so that 生成的提取规则可以被回归测试。
94. As an API 测试 Agent 使用者, I want Agent Evaluation 覆盖 suite dependency coverage, so that 链路依赖不是摆设。
95. As an API 测试 Agent 使用者, I want Agent Evaluation 覆盖 suite failure classification, so that 前置失败、变量失败和下游失败可以被自动评估。
96. As an API 测试 Agent 使用者, I want Agent Evaluation 覆盖 memory reuse in suite generation, so that 历史链路经验确实影响下一次生成。
97. As an API 测试 Agent 使用者, I want 所有 V3 能力在 fake provider 下可测试, so that CI 不依赖真实 LLM、真实 embedding 或真实外部 API。
98. As an API 测试 Agent 使用者, I want V3 保持 HTTP API 测试边界, so that 系统不会偏到 UI 自动化、Service 直调或 DB 直连断言。
99. As an API 测试 Agent 使用者, I want V3 的实现保持 Java/Spring 主干, so that 现有领域模型、事务和测试体系可以继续复用。
100. As an API 测试 Agent 使用者, I want V3 完成后形成可面试讲解的 Agent 故事, so that 我能清楚讲出业务链路理解、工具执行、变量上下文、失败恢复和记忆反馈。
101. As an API 测试 Agent 使用者, I want 一个 V3 Light Console, so that 没有完整产品前端时我也能手动测试和演示智能链路 Agent。
102. As an API 测试 Agent 使用者, I want 在 V3 Light Console 中选择本地 fixture, so that 我可以快速复现订单、支付、鉴权等典型链路样例。
103. As an API 测试 Agent 使用者, I want 在 V3 Light Console 中切换 fake provider 和 manual real LLM, so that 我可以对比确定性链路和真实模型建议。
104. As an API 测试 Agent 使用者, I want 在 V3 Light Console 中查看生成的 SUITE 草稿, so that 我可以确认步骤顺序、extractRules、变量引用和 critical 标记。
105. As an API 测试 Agent 使用者, I want 在 V3 Light Console 中查看执行结果和变量审计, so that 我可以理解每个变量从哪里提取、写到哪里、被哪个步骤消费。
106. As an API 测试 Agent 使用者, I want 在 V3 Light Console 中查看失败分析和记忆反馈, so that 我可以演示 Agent 如何解释链路失败并沉淀经验。
107. As an API 测试 Agent 使用者, I want V3 Light Console 保持只读或受控操作, so that 它不会绕过后端 Policy Validator、readiness validation 和 Human-in-the-loop 边界。

## Implementation Decisions

- V3 定位为“智能链路测试 Agent”，核心对象仍然是 HTTP API 测试，不扩展 UI 自动化、Service 直调、DB 直连断言或浏览器交互。
- V3 不推翻 V1/V2 架构。继续复用 Task、PlanStep、StepOutcome、ApiSpec、TestCase、TestCaseDraft、ExecutionRecord、Observation、Report、Knowledge RAG、Memory Refinery、Controlled Planner、Policy Validator、Replanning Loop、Human-in-the-loop 和 Agent Evaluation Harness。
- SUITE 仍然表示多个 HTTP 接口按顺序组成的链路用例。V3 对 SUITE 做增强，而不是引入新的测试类型。
- SINGLE 和 SUITE 继续保持不同生成路径。SINGLE 仍然是单接口驱动，SUITE 通过独立链路逻辑组装 steps。
- SUITE 继续采用“先有 SINGLE，后有 SUITE”的策略。每个链路步骤优先复用对应 SINGLE case 的请求模板和断言，再补充链路变量、提取规则和步骤元数据。
- SUITE TestCaseStep 继续遵循快照冻结原则。生成时写入 request template、assertion definitions、extractRules、变量引用、critical 标记和来源元数据；后续 SINGLE case 或 ApiSpec 变化不会自动修改既有 SUITE。
- V3 链路发现输入包括用户选择的 ApiSpec、ApiSpec 结构、接口路径和 HTTP 方法、字段名和 DTO 线索、Knowledge RAG 召回的 business_flow / api_note / test_spec、LongTermMemory 召回的 failure_pattern / testing_pattern / preference。
- 链路发现输出应包含候选链路、步骤顺序、置信度、证据来源、阻塞原因和是否需要人工确认。
- 低置信度链路不得在全自动模式下直接执行。应生成草稿并进入人工确认，或让 Planner 输出 WAIT_FOR_HUMAN。
- 新增或增强 DependencyLinker 概念，用于识别生产者步骤、消费者步骤、变量名、源路径、目标作用域和下游引用。
- DependencyLinker 必须同时生成 `extractRules` 和后续步骤变量引用，避免“生产者抽取规则”和“消费者引用变量”由不同模块各自推断导致不一致。
- 代码分析模块只提供结构线索，不直接负责生成 extractRules。
- 规则系统只负责单步输入约束变异，不负责步骤间变量依赖推导。
- 执行引擎只消费 extractRules、变量引用和执行策略，不决策生成链路依赖。
- 引入 ExecutionContext 或等价运行时上下文，统一承载 env、task、suite、case、step、fn、data 等作用域。
- ExecutionContext 应支持步骤级上下文隔离：每个 step 执行时有当前 step scope，同时可读 suite、task、env、case 等上级作用域。
- 引入 ContextStore 或等价机制，负责运行时变量读写、作用域查询和审计事件记录。
- 引入 VariableResolver 或增强现有请求构建器，把 `${scope.path}` 变量表达式解析为实际值。
- 变量表达式必须要求作用域前缀。V3 不支持裸变量名兼容，例如 `${token}` 不带 scope 的写法不进入首版。
- 变量解析应支持对象路径和简单数组下标，例如 `${suite.order.id}`、`${step.createOrder.items[0].id}`。
- 变量解析失败应返回结构化错误，包含 missing variable、scope、path、stepId、caseId 和引用位置。
- 引入 DynamicValueProvider 或等价机制，支持 `fn.*` 和 `data.*` 基础动态值。
- V3 内置动态值只覆盖常用测试数据和简单函数，不引入通用表达式 DSL、脚本引擎或复杂函数嵌套。
- 引入 ResponseExtractor 或等价机制，从 HTTP 响应中按 extractRules 提取变量。
- ResponseExtractor 首版支持 BODY_JSON、HEADER、STATUS_CODE 三类来源。
- extractRules 应至少包含 rule id、source type、source path、target scope、target key、required、failure strategy、default value、description。
- failure strategy 首版支持 FAIL_FAST、WRITE_NULL、WRITE_DEFAULT。
- VariableWriteBackService 或等价机制负责将 ResponseExtractor 输出写回 ExecutionContext。
- 变量写回必须记录审计事件，包括变量名、作用域、来源 step、来源 response path、旧值摘要、新值摘要、是否覆盖、时间戳。
- 审计、日志、报告中必须脱敏 authorization、cookie、password、secret、token 等敏感字段。
- SUITE 执行前应增加 readiness validation，检查步骤顺序、变量生产消费关系、extractRules 必填字段、变量引用格式、基础 env/auth 变量是否可解析。
- SUITE 执行时，每个 step 进入执行前应从 ExecutionContext 构建请求；执行后应运行断言、提取变量、写回上下文、记录 step result。
- SUITE 执行策略支持 FAIL_FAST、CONTINUE_ON_FAILURE、CONTINUE_IF_NON_CRITICAL。
- 每个 TestCaseStep 应支持 `critical` 标记；关键步骤失败时根据策略跳过后续依赖步骤。
- SUITE 执行结果应包含 step results、overall status、first failing step、dependent skipped steps、context snapshot summary、variable audit summary 和 failure summary。
- ExecutionRecord 应继续作为原始事实层。SUITE 可以保留一个 suite-level ExecutionRecord，同时在 response snapshot 中记录步骤详情；如后续实现需要，也可以补充 step-level execution reference，但不得破坏现有查询和报告。
- FailureAnalysis 应增强 suite failure 分类，区分前置步骤失败、变量提取失败、变量解析失败、链路顺序错误、业务前置条件失败和下游接口失败。
- Observation 应记录链路失败分析结论，包含 root step、affected downstream steps、变量证据、依赖证据和 next suggestion。
- Memory Feedback 应接入 V3 链路事件。高置信度的链路顺序、变量依赖、业务前置条件、常见变量提取路径和失败模式可以进入 Memory Candidate。
- 用户在半自动模式下对链路顺序、extractRules、变量引用和 critical 标记的修正，应作为 Memory Feedback 的候选来源。
- UnifiedContextBuilder 可以继续为 SUITE 生成提供 RAG 和 Memory 上下文；V3 不要求重写 RAG / Memory，只消费当前核心版能力。
- V3 可以补少量面向链路的 RAG query tags，例如 business_flow、suite-dependency、precondition、extract-rule，但不实现完整版 Query Rewrite、多路召回或父子索引。
- 真实 LLM 接入采用分阶段策略：V3 前期先用确定性代码和 fake provider 跑通链路系统；V3 中期引入真实 LLM 作为候选建议层；V3 后期用真实 LLM 做手动 Agent Evaluation 对比。
- 真实 LLM 在 V3 中只能参与“建议”，不能直接执行工具、修改数据库或绕过 Java 侧校验。它可以建议业务链路、步骤顺序、变量依赖、extractRules 和失败解释。
- 真实 LLM 输出必须经过结构化 schema 解析、Policy Validator、readiness validation 和必要的人类确认，才能进入 SUITE 生成或执行。
- V3 应保留 `providerMode` 或等价配置，区分 fake deterministic 模式、manual real LLM 模式和后续实验模式。
- CI、默认 Maven 测试和同事协作开发流程必须继续使用 fake provider；真实 LLM 不作为 V3 自动验收和回归测试的必需条件。
- V3 需要提供无前端手动测试入口，建议命名为 Manual Suite Agent Harness 或等价能力。它通过后端命令、Maven profile、专用 integration test 或 Spring Boot runner 启动固定 fixture，跑完整“链路发现 -> SUITE 生成 -> 执行 -> 失败分析 -> 记忆反馈 -> 报告”流程。
- Manual Suite Agent Harness 应支持 fake deterministic 模式和 manual real LLM 模式。fake 模式用于稳定复现；manual real LLM 模式用于本地观察真实模型对链路发现、DependencyLinker、extractRules 和失败解释的建议质量。
- Manual Suite Agent Harness 应从本地 fixture 或测试资源加载 ApiSpec、KnowledgeDocument、LongTermMemory、环境变量、鉴权变量和 fake HTTP 响应，不要求前端输入。
- Manual Suite Agent Harness 每次运行应输出结构化产物，例如 run summary、generated suite draft、execution result、variable audit、failure analysis、memory feedback summary、evaluation comparison，并保存为 JSON 和 Markdown 文件，方便没有前端时人工查看。
- Manual Suite Agent Harness 不替代正式 REST API 或 Web Console。它是 V3/V4 开发和面试演示阶段的后端手动验证入口。
- V3 可以新增一个极简 V3 Light Console，但它只服务本地手动测试和面试演示，不作为完整产品化前端。
- V3 Light Console 应优先消费 Manual Suite Agent Harness 或等价后端入口，不在前端实现链路发现、变量解析、失败分析或策略校验逻辑。
- V3 Light Console 首版只需要支持：选择 fixture、选择 provider mode、启动一次 V3 run、查看 run summary、查看 SUITE 草稿、查看步骤执行结果、查看变量审计、查看失败分析、查看 memory feedback、查看 Markdown/JSON 报告。
- V3 Light Console 可以使用最小技术栈，例如 Spring Boot 静态页面、单页 HTML + fetch、或非常轻量的前端工程。选择标准是本地可运行、容易演示、少引入依赖。
- V3 Light Console 不做登录、权限、团队协作、任务列表管理、复杂编辑器、拖拽编排、可视化工作流设计器或生产级 UI。
- V3 Light Console 中的真实 LLM 开关必须是显式手动模式，不能默认启用，也不能进入 CI。
- Controlled Planner 应能感知 SUITE readiness 和链路风险。当变量、前置依赖或人工确认缺失时，Planner 应等待或重规划，而不是直接执行。
- Policy Validator 应继续约束高风险执行。低置信度链路、缺失环境变量、缺失鉴权变量、危险 host 或越界工具都不能直接执行。
- Replanning Loop 应支持链路失败恢复，例如插入补充环境变量、等待人工确认、重新生成 extractRules、重新排序步骤或转入失败分析。
- Human-in-the-loop 应支持 V3 链路 review，包括确认链路顺序、编辑 extractRules、编辑变量引用、补充 env/auth/test data、确认高风险执行。
- ReportGeneration 应增强 SUITE 报告内容，展示链路摘要、步骤结果、变量传递、失败根因、依赖跳过、RAG/Memory 引用和学习结果。
- AgentEvaluation 应增加 V3 fixture，覆盖链路发现、DependencyLinker、extractRules、变量解析、变量提取、变量写回、suite 执行、suite 失败分类和 memory reuse。
- CI 默认使用 fake LLM、fake embedding、fake HTTP gateway 和固定 fixture。真实 LLM 和真实目标服务只能作为手动实验模式，不作为 V3 自动测试必需条件。
- V3 不新增完整 Web Console、产品化 REST Controller、GitHub Actions 集成、大屏、权限系统、团队协作或商业化配置中心；但允许为了 V3 Light Console 暴露最小本地调试端点。
- V3 不实现完整版 RAG + Memory。真实 BGE-M3 / OpenAI embedding、pgvector、BM25、Query Rewrite、多路召回、Small-to-Big 父子索引和记忆治理完整版放到 V4。
- V3 不实现复杂表达式 DSL、通用脚本引擎、跨任务变量共享、Secrets 托管平台或可视化变量编排。
- V3 不要求自动发现全项目所有业务流程。首版聚焦用户选择范围内的 ApiSpec、明确 business_flow 文档和高置信度字段关系。
- V3 不自动覆盖旧 SUITE。旧 SUITE 需要显式刷新并保留变更前镜像。
- V3 必须保持 Maven 测试稳定通过，不引入外部服务作为默认测试依赖。

## Implementation Phases

V3 按 6 个 phase 交付。这个分期是为了让同事可以并行或连续协作，也为了让每一段都有清晰的面试叙事和可验收产物。后续 `$to-issues` 应围绕这些 phase 拆成可独立领取的 issue。

### V3-1：Manual Suite Agent Harness 与演示 fixture

目标：先把“能手动验证 V3 Agent 闭环”的底座搭好，避免后续能力只能靠单元测试碎片证明。

主要产物：

- Manual Suite Agent Harness 或等价后端入口。
- 固定 fixture：ApiSpec、business_flow 文档、LongTermMemory、env/auth/test data、fake HTTP response。
- fake provider / fake HTTP gateway 下的一键运行链路。
- JSON 和 Markdown 输出：run summary、generated suite draft、execution result、variable audit、failure analysis、memory feedback summary、evaluation comparison。
- 敏感变量脱敏、manual real LLM 显式开关和默认 fake provider 边界。

验收口径：

- 不需要前端，也能通过单条命令、Maven profile、Spring Boot runner 或 integration test 跑出完整 V3 演示报告。
- 默认不依赖真实 LLM、真实 embedding 或真实目标 HTTP 服务。
- 输出产物能让人看懂一次链路任务从输入、生成、执行、失败分析到记忆反馈的全过程。

### V3-2：Business Flow Discovery

目标：让 Agent 能从接口资产、业务文档、RAG 上下文、长期记忆和字段语义里识别候选业务链路。

主要产物：

- Business Flow Discovery 或等价能力。
- 候选链路模型：steps、order、confidence、evidence、blockers、requiresHumanReview。
- 基于 ApiSpec path、HTTP method、DTO 字段、business_flow 文档、MemoryItem 的链路排序和证据归因。
- 低置信度链路进入人工确认或 WAIT_FOR_HUMAN，不在全自动模式直接执行。

验收口径：

- 给定订单、支付、鉴权类 fixture，系统能生成可解释的候选链路。
- 每条候选链路能说明来源证据：来自文档、接口命名、字段关系、RAG 还是 Memory。
- 证据不足时不会自信执行，而是返回 blocker 或等待人审。

### V3-3：Dependency Linker 与 SUITE 草稿生成

目标：让 Agent 不只知道步骤顺序，还能知道“哪个步骤产出变量、哪个步骤消费变量”，并生成可执行 SUITE 草稿。

主要产物：

- DependencyLinker 或等价模块。
- producer / consumer 变量依赖模型。
- 成对生成 `extractRules` 和 `${suite.xxx}` / `${step.xxx}` 变量引用。
- SUITE steps 的 stepId、stepName、order、critical、source refs、basedOnApiSpecIds、basedOnKnowledgeRefs、basedOnMemoryRefs。
- SUITE readiness validation 的生成侧校验：步骤顺序、变量生产消费、extractRules 必填字段、变量引用格式。

验收口径：

- 创建订单响应中的 `orderId` 能被支付、查询或取消订单步骤自动消费。
- `extractRules` 和下游变量引用由同一个依赖推导结果产生，不出现“抽取了 A 但引用 B”的脱节。
- 低置信度或不完整依赖不会生成可直接执行的高风险 SUITE。

### V3-4：ExecutionContext、变量解析、响应提取与写回

目标：让 SUITE 真正具备运行时数据流，而不是只保存静态步骤。

主要产物：

- ExecutionContext / ContextStore 或等价运行时上下文。
- env、task、suite、case、step、fn、data 等作用域。
- VariableResolver：解析 `${scope.path}`，支持对象路径和简单数组下标。
- DynamicValueProvider：uuid、now、randomEmail、randomPhone、randomFrom 等基础动态值。
- ResponseExtractor：从 JSON body、header、statusCode 提取变量。
- VariableWriteBackService：写回上下文并生成变量审计。
- 执行前 readiness validation：变量缺失、无效路径、无效 extractRules、消费者排在生产者前等问题提前阻断。

验收口径：

- step1 响应提取变量后，step2 能通过 `${suite.xxx}` 或 `${step.stepName.xxx}` 成功注入请求。
- 变量解析失败、提取失败、写回覆盖都能产生结构化诊断和审计。
- token、cookie、authorization、password、secret 等敏感值不会泄露到日志、报告或 Light Console。

### V3-5：Suite Failure Analysis、Replanning 与 Human-in-the-loop

目标：让 Agent 能解释链路为什么失败，并在可恢复场景下进入重规划或人类确认。

主要产物：

- suite failure classification 增强：PREREQUISITE_STEP_FAILURE、VARIABLE_EXTRACTION_FAILURE、VARIABLE_RESOLUTION_FAILURE、DEPENDENCY_ORDER_FAILURE、BUSINESS_PRECONDITION_FAILURE、DOWNSTREAM_API_FAILURE。
- Suite Failure Analysis 输出 root step、affected downstream steps、variable evidence、dependency evidence、nextSuggestion。
- Observation 写入链路失败分析结论。
- Controlled Planner 感知 SUITE readiness 和链路风险。
- Replanning Loop 支持重新生成 extractRules、重新排序步骤、补充变量、等待人工确认或转入失败分析。
- Human-in-the-loop 支持 review 链路顺序、extractRules、变量引用、critical 标记、env/auth/test data。

验收口径：

- 前置步骤失败不会被误判为下游接口失败。
- 变量提取失败、变量注入失败和真实下游失败能被区分。
- 低置信度链路、高风险执行、缺失变量和缺失鉴权不能绕过 Policy Validator 或人审边界。

### V3-6：Memory Feedback、Agent Evaluation 与 V3 Light Console

目标：补齐 V3 的演示闭环和质量闭环，让智能链路 Agent 能被评测、能沉淀经验、能被本地页面直观看到。

主要产物：

- V3 Memory Feedback：链路顺序修正、extractRules 修正、变量引用修正、业务前置条件、失败模式进入 Memory Candidate。
- V3 Agent Evaluation fixture：覆盖 suite dependency coverage、extractRules 正确性、变量传递、失败分类、memory reuse。
- fake provider 与 manual real LLM 的手动对比入口。
- V3 Light Console：选择 fixture、选择 provider mode、启动一次 V3 run、查看 SUITE 草稿、步骤结果、变量审计、失败分析、Memory Feedback、Markdown/JSON 报告。
- 为 Light Console 暴露最小本地调试端点，但不做完整产品化 REST Controller。

验收口径：

- 完整 Maven 测试仍默认使用 fake provider 并稳定通过。
- 手动 real LLM 模式只能显式开启，不进入 CI。
- Light Console 只展示和触发受控后端能力，不在前端实现链路发现、变量解析、失败分析或策略校验。
- 面试演示时可以从一个页面说明 Agent 如何发现链路、生成变量规则、执行链路、解释失败并沉淀记忆。

## Testing Decisions

- 最高测试接缝是应用层。生成侧优先从现有 SUITE 用例生成入口断言外部行为；执行侧优先从现有 SUITE HTTP 执行入口断言外部行为。
- 如果新增 V3 链路工作流应用服务，它只能作为薄编排入口测试“发现 -> 生成 -> 执行 -> 分析 -> 反馈”的完整纵切，不应把底层模块私有实现暴露给测试。
- 测试应断言外部行为：生成的 SUITE steps、步骤顺序、extractRules、变量引用、critical 标记、来源引用、执行结果、变量审计、失败分类、Observation、Memory Candidate 和 Report。
- 测试不应断言私有 helper、内部排序临时变量、prompt 文案、日志文本、具体字符串拼接或非契约化 Map 遍历顺序。
- 需要新增链路发现测试：给定 ApiSpec 集合和 business_flow 知识文档，应生成正确候选链路、顺序、置信度和 evidence。
- 需要新增低置信度链路测试：证据不足时不应直接自动执行，应进入人工确认或返回 blocker。
- 需要新增 DependencyLinker 测试：给定上游响应字段和下游请求字段，应生成成对的 extractRules 和变量引用。
- 需要新增 extractRules 校验测试：缺少 source path、target key、target scope 或无效 rule 时应阻止执行并返回结构化诊断。
- 需要新增 VariableResolver 测试：覆盖 env、task、suite、case、step、fn、data 作用域解析。
- 需要新增变量解析失败测试：缺失变量、错误路径、错误数组下标应产生明确 blocked result。
- 需要新增 DynamicValueProvider 测试：覆盖 uuid、now、randomEmail、randomPhone、randomFrom 等基础函数。
- 需要新增 ResponseExtractor 测试：覆盖 JSON body、header、status code 三类来源。
- 需要新增提取失败策略测试：覆盖 required + FAIL_FAST、WRITE_NULL、WRITE_DEFAULT。
- 需要新增 VariableWriteBack 测试：覆盖写入、覆盖、作用域隔离和审计记录。
- 需要新增敏感信息脱敏测试：变量审计、执行快照、报告和诊断中不得泄露 token、cookie、authorization、password、secret。
- 需要新增 SUITE 执行上下文测试：step1 提取变量后，step2 能通过 `${suite.xxx}` 或 `${step.stepName.xxx}` 成功引用。
- 需要新增 SUITE 顺序依赖测试：消费者步骤排在生产者前面时，执行前 readiness validation 应失败。
- 需要新增 SUITE fail strategy 测试：覆盖 FAIL_FAST、CONTINUE_ON_FAILURE、CONTINUE_IF_NON_CRITICAL。
- 需要新增 critical step 测试：关键步骤失败应跳过下游依赖，非关键步骤失败可按策略继续。
- 需要新增 suite failure classification 测试：覆盖前置步骤失败、变量提取失败、变量解析失败、链路顺序错误、业务前置条件失败、下游接口失败。
- 需要新增 Observation 写入测试：链路失败分析应生成结构化 Observation，并包含 root step、affected steps 和 variable evidence。
- 需要新增 Memory Feedback 测试：用户修正链路顺序、extractRules 或变量引用后，应生成可提纯的 Memory Candidate。
- 需要新增 Report 测试：SUITE 报告应包含链路摘要、步骤结果、变量传递摘要、失败根因、依赖跳过、引用来源和记忆学习结果。
- 需要新增 Agent Evaluation 测试：V3 fixture 应能评估 suite dependency coverage、extractRules 正确性、变量传递、失败分类和记忆复用。
- 需要新增 provider mode 边界测试：默认测试必须走 fake provider；manual real LLM 模式不能被 CI 默认启用。
- 需要新增真实 LLM 手动评测说明或入口：允许开发者在本地配置真实 LLM 后对链路发现、DependencyLinker 和失败解释做实验性对比，但结果不得影响默认验收。
- 需要新增 Manual Suite Agent Harness 测试：在 fake provider 和 fake HTTP gateway 下，单条命令或单个 integration test 能跑完整 V3 链路并生成 JSON/Markdown 结果文件。
- 需要新增 Manual Suite Agent Harness 输出测试：输出文件应包含 generated suite、step order、extractRules、variable references、execution result、variable audit、failure classification、memory feedback 和 report summary。
- 需要新增 Manual Suite Agent Harness 安全测试：输出文件必须脱敏敏感变量，且 manual real LLM 模式不能在没有显式配置时启动。
- 需要新增 V3 Light Console 测试：页面应能加载 fixture 列表、启动 fake provider run、展示 SUITE 草稿、步骤结果、变量审计、失败分析和报告摘要。
- V3 Light Console 测试应只断言用户可见行为和后端契约，不断言 CSS 细节、DOM 私有结构或具体样式实现。
- 需要新增 V3 Light Console 安全测试：真实 LLM 模式默认关闭，敏感变量在页面上脱敏，前端不能绕过后端策略校验直接执行高风险链路。
- 需要新增 boundary guard 测试：V3 不应引入 UI 自动化、Service 直调、DB 直连断言、真实外部 LLM 必需依赖、真实外部 API 必需依赖或完整 Web Console。
- 需要新增迁移测试：如果引入新的表或字段，H2 test profile 和 PostgreSQL 迁移必须通过。
- 需要保留并扩展现有测试先例：SUITE 生成测试、HTTP SUITE 执行测试、失败分析测试、Memory Feedback 测试、Agent Evaluation 测试和 orchestration/replanning 测试。
- 完整验收应运行后端 Maven 测试套件，并确保 fake provider 模式下所有 V3 行为可重复。

## Out of Scope

- 不做完整版 RAG。真实 embedding provider、pgvector、BM25、多路召回、Query Rewrite、Small-to-Big 父子索引和 RAG 反馈调权完整实现放到 V4。
- 不做完整版 Memory 治理。记忆衰减、复杂版本治理、跨项目记忆隔离、人工记忆管理台和 mem0 集成放到后续版本。
- 不做完整 Web Console、团队协作界面、权限系统或可视化链路编排；V3 只允许做面向本地手动测试和面试演示的 V3 Light Console。
- 不做完整 REST Controller 产品化接口。V3 可以为 Light Console 暴露最小本地调试端点，但主线仍然后端应用层、Manual Suite Agent Harness 和测试闭环为主。
- 不做 UI 自动化、浏览器自动化、Service 直调、DB 直连断言、消息队列测试或非 HTTP 协议测试。
- 不做复杂表达式 DSL、脚本引擎、函数嵌套、字符串拼接公式或通用变量计算平台。
- 不做跨任务长期变量共享、Secrets 托管系统或外部配置中心。
- 不做全项目自动业务流程挖掘。V3 首版只在用户选择范围、明确文档和高置信度线索内推断链路。
- 不要求真实 LLM、真实 embedding 或真实目标 HTTP 服务参与 CI；真实 LLM 只作为 V3 中后期的本地手动评测能力。
- 不自动修改既有 SUITE 资产。旧资产刷新必须显式触发。

## Further Notes

V3 是 ProbeFlow 面试叙事里非常关键的一版。

V1 可以讲“确定性 API 测试后端闭环”。

V2 可以讲“受控 Agent Core：规划、工具契约、策略校验、恢复、人审、记忆、评估”。

V3 要讲“Agent 真正理解业务链路”：它不是只生成单接口用例，而是能把多个接口组织成带数据流的业务流程，并在执行时维护上下文、提取变量、注入变量、分析链路失败、沉淀经验。

推荐面试表述：

```text
我把链路测试拆成四层：

第一层是 Business Flow Discovery，从 ApiSpec、RAG 文档、接口命名和历史记忆中识别候选业务流程。

第二层是 Dependency Linker，识别哪个步骤产出变量、哪个步骤消费变量，并成对生成 extractRules 和变量引用。

第三层是 Execution Context，执行时维护 env、task、suite、case、step 等作用域，支持变量解析、响应提取、写回和审计。

第四层是 Suite Failure Analysis，把前置步骤失败、变量提取失败、变量注入失败和真实下游失败区分开，并把高价值经验反馈给长期记忆。

这样 Agent 不只是会调工具，而是能理解业务流程、维护数据流、解释失败，并越用越懂项目。
```

V3 做完后，ProbeFlow 已经具备比较强的 Agent 项目展示价值。V4 再补完整版 RAG + Memory，会让系统从“会理解链路”进一步升级到“上下文和长期经验更准、更可治理”。

真实 LLM 的接入节奏应这样讲：

```text
V3 前期：先用确定性规则和 fake provider 做出可执行链路闭环。
V3 中期：接入真实 LLM，让它提出业务链路、变量依赖和 extractRules 候选。
V3 后期：用真实 LLM 跑手动评测，对比 fake / real 在链路发现和失败解释上的差异。
CI 默认：始终使用 fake provider，保证测试稳定。
```

这个顺序能体现 ProbeFlow 的工程化取舍：不是裸接 LLM，而是先有确定性边界、工具契约、策略校验、readiness validation 和人类确认，再让 LLM 提供高价值建议。

没有前端时，V3 的手动测试方式不是“点页面”，而是通过后端 harness 跑固定样例：

```text
1. 本地 fixture 准备 ApiSpec、业务流程文档、长期记忆、环境变量和 fake HTTP 响应。
2. Manual Suite Agent Harness 启动一次 V3 链路任务。
3. 系统生成 SUITE 草稿、执行步骤、提取变量、写回上下文、分析失败、输出报告。
4. 开发者查看本地 JSON / Markdown 结果文件，判断链路、变量、失败归因和 LLM 建议是否正确。
```

等到后续 V5 做产品化接口和 Web Console，再把这个后端 harness 背后的能力接到页面上。

如果 V3 要做前端，应该叫 V3 Light Console，而不是正式 Web Console。它的目标是“演示和调试 Agent 能力”，不是“做一个完整平台”。推荐第一屏就是任务运行台：

```text
左侧：fixture / provider mode / run 按钮
中间：生成的 SUITE steps、extractRules、变量引用
右侧：执行结果、变量审计、失败分析、Memory Feedback
底部：Markdown 报告预览和 JSON 原文
```

这能让面试官一眼看到 Agent 是怎么从业务链路生成、变量传递、执行失败、分析原因到沉淀记忆的。
