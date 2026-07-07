状态：ready-for-agent

# ProbeFlow V2 Phase 4：Policy Validator 与安全拦截 PRD

## Problem Statement

ProbeFlow V2 已经完成了 LLM Provider、Tool Contract / AgentPolicy，以及 Controlled Planner。现在系统已经能够让 Planner 基于任务状态、上下文摘要、上一步结果和可用工具，产出一个结构化的 `PlanDecision`。

但这还不是一个可以安全执行的 Agent。

当前最大的问题是：Planner 已经能“建议下一步”，但系统还缺少一道位于 Planner 和执行层之间的强制安全门，用来判断这个建议是否越界、是否顺序错误、是否需要人工确认、是否违反 V1 后端 API 测试边界。

如果没有 Policy Validator，后续一旦接入真实 LLM Planner，就会出现几个风险：

- LLM 建议执行不存在或未注册的工具。
- LLM 建议在错误阶段执行工具，例如还没生成测试用例就执行 HTTP 请求。
- LLM 建议调用 V1 明确禁止的能力，例如 UI 自动化、浏览器自动化、直接调用 service、直接断言数据库。
- LLM 建议绕过人工确认，执行高风险或写操作工具。
- LLM 建议访问外部系统，例如通知、工单、GitHub、Jira、Slack、Webhook、真实 CI。
- Planner 返回 `BLOCKED` 或 `FAILED` 状态时，下游仍然误把它当成可执行建议。
- 审计日志里只能看到 Planner 说了什么，无法解释系统为什么允许、拦截或要求人工确认。

从用户视角看，V2 的 Agent Core 需要证明一件事：

```text
LLM 可以参与决策，但不能越过 Java 侧确定性的策略边界。
```

Phase 4 要补上的就是这道边界。

## Solution

新增一个 Policy Validator 安全拦截层，放在 Controlled Planner 和后续 Tool Router / PlanStepRunner 之间。

目标链路是：

```text
PlannerInput
-> ControlledPlanner
-> PlanDecision
-> PolicyValidator
-> PolicyValidationResult
-> 后续 ToolRouter / PlanStepRunner
```

Phase 4 只负责验证和解释，不负责真实执行工具。

Policy Validator 接收 Planner 产出的 `PlanDecision`，结合当前 `PlannerInput`、`AgentPolicy`、Tool Contract、可用工具列表、任务阶段和工作流模式，输出结构化的 `PolicyValidationResult`：

- `ALLOWED`：Planner 建议在当前边界内，可以交给后续执行层。
- `REQUIRES_HUMAN_CONFIRMATION`：建议方向合理，但必须等待人工确认。
- `BLOCKED`：建议越界、顺序错误、工具不可用、前置条件不足或违反 V1 边界。

每次校验都必须给出可解释的 reason code、面向审计的 blocker 详情，以及可用于后续 Agent Loop 的简洁摘要。

Phase 4 的核心价值不是“再包一层 if 判断”，而是把 Agent 的安全策略变成一个稳定、可测试、可讲清楚的领域模块：

```text
LLM 负责提出建议。
Policy Validator 负责决定建议能否进入执行层。
Tool Router 以后只执行已经被验证过的动作。
```

测试接缝选择最高层的 `PolicyValidatorService`。测试应从外部行为出发，构造 `PlanDecision + PlannerInput + AgentPolicy`，断言最终 validation status、reason code、blocker、human confirmation 要求，而不是测试内部私有判断函数。

## User Stories

1. As an Agent 系统开发者, I want PlannerDecision 在执行前经过统一校验, so that LLM 建议不能直接进入执行层。
2. As an Agent 系统开发者, I want Policy Validator 返回结构化结果, so that 后续 Agent Loop 可以稳定消费允许、拦截和人工确认状态。
3. As an Agent 系统开发者, I want 校验结果包含 reason code, so that 每一次拦截都可以被测试和审计。
4. As an Agent 系统开发者, I want 校验结果包含用户可读 message, so that 后续控制台或日志能解释 Agent 为什么停下。
5. As an Agent 系统开发者, I want 校验结果包含 blocker 列表, so that Replanning Loop 后续可以知道缺什么条件。
6. As an Agent 系统开发者, I want `PROPOSED` 状态之外的 PlannerDecision 默认不能执行, so that `BLOCKED` 和 `FAILED` 不会被误当成可执行建议。
7. As an Agent 系统开发者, I want `FAILED` PlannerDecision 被明确拦截, so that LLM 调用失败不会造成错误执行。
8. As an Agent 系统开发者, I want `BLOCKED` PlannerDecision 被转换成阻塞结果, so that 系统可以保留 Planner 给出的 blockers。
9. As an Agent 系统开发者, I want `WAIT_FOR_HUMAN` 动作输出人工确认结果, so that Agent 能显式进入等待人类输入状态。
10. As an Agent 系统开发者, I want `WAIT_FOR_HUMAN` 必须携带可执行的人类输入请求, so that 用户知道需要补充什么。
11. As an Agent 系统开发者, I want 缺少人类输入请求的 `WAIT_FOR_HUMAN` 被拦截, so that Agent 不会进入不可恢复的等待状态。
12. As an Agent 系统开发者, I want `CONTINUE` 动作在无新增工具调用时可以通过, so that 确定性编排可以继续推进。
13. As an Agent 系统开发者, I want `INSERT_STEP` 必须包含可验证的 proposed step, so that 系统不会插入空步骤。
14. As an Agent 系统开发者, I want `INSERT_STEP` 必须绑定已注册或当前可见的工具, so that Planner 不能凭空发明能力。
15. As an Agent 系统开发者, I want `REPLAN` 动作不直接触发执行, so that 重新规划和工具执行保持分离。
16. As an Agent 系统开发者, I want `STOP` 动作只作为安全终止建议通过, so that 停止任务不会被混成工具调用。
17. As an Agent 系统开发者, I want Planner 提议的 tool name 必须符合 ToolName 规范, so that 非法名称在安全门被拦截。
18. As an Agent 系统开发者, I want Planner 提议的工具必须存在于 Tool Contract Registry, so that 未登记工具无法执行。
19. As an Agent 系统开发者, I want Planner 提议的工具必须出现在当前 PlannerInput 的 available tools 中, so that Planner 只能使用当前上下文暴露给它的能力。
20. As an Agent 系统开发者, I want Planner 提议的工具必须被 AgentPolicy 白名单允许, so that 租户或任务策略可以限制 Agent 能力。
21. As an Agent 系统开发者, I want Planner 提议的工具必须匹配当前 AgentTaskPhase, so that Agent 不会跨阶段执行。
22. As an Agent 系统开发者, I want API 分析工具只在任务初始化阶段允许, so that API 解析不会在后续阶段被反复误触发。
23. As an Agent 系统开发者, I want Knowledge 和 Memory 工具只在上下文构建阶段允许, so that RAG / Memory 构建边界清晰。
24. As an Agent 系统开发者, I want 测试用例生成工具只在测试设计阶段允许, so that 用例生成不会被执行阶段随意插入。
25. As an Agent 系统开发者, I want HTTP 执行工具只在执行阶段允许, so that Agent 不会提前发起真实请求。
26. As an Agent 系统开发者, I want 失败分析工具只在失败分析阶段允许, so that 没有失败信号时不会生成伪分析。
27. As an Agent 系统开发者, I want 报告工具只在报告阶段允许, so that 报告不会在任务信息不足时生成。
28. As an Agent 系统开发者, I want Policy Validator 检查工具前置条件, so that 缺少 API spec、测试用例、执行结果等条件时能阻止执行。
29. As an Agent 系统开发者, I want Policy Validator 检查工具输入 schema, so that 缺少必填字段或类型错误时能提前失败。
30. As an Agent 系统开发者, I want Policy Validator 复用已有 AgentPolicyService 能力, so that Tool Contract 和 AgentPolicy 的规则不会重复实现。
31. As an Agent 系统开发者, I want 高风险工具自动进入人工确认, so that Agent 不会静默执行危险操作。
32. As an Agent 系统开发者, I want contract 标记为 human confirmation required 的工具进入人工确认, so that 工具作者可以声明安全门。
33. As an Agent 系统开发者, I want review required workflow 下的非只读工具进入人工确认, so that 审核模式下所有写操作都受控。
34. As an Agent 系统开发者, I want 低置信度 PlannerDecision 被阻止或要求人工确认, so that Agent 不会在不确定时强行推进。
35. As an Agent 系统开发者, I want Policy Validator 拦截 UI 自动化相关工具, so that V2 仍保持后端 API 测试边界。
36. As an Agent 系统开发者, I want Policy Validator 拦截浏览器自动化相关工具, so that Phase 4 不扩展到端到端 UI 测试。
37. As an Agent 系统开发者, I want Policy Validator 拦截 service 直调工具, so that Agent 必须通过领域 ApplicationService 或 HTTP 边界推进。
38. As an Agent 系统开发者, I want Policy Validator 拦截数据库直接断言工具, so that 测试观察不绕过用户可见 API 行为。
39. As an Agent 系统开发者, I want Policy Validator 拦截通知类工具, so that Phase 4 不引入 Slack、邮件、Webhook 等外部副作用。
40. As an Agent 系统开发者, I want Policy Validator 拦截工单类工具, so that Phase 4 不创建 Jira、GitHub issue 或其他外部任务。
41. As an Agent 系统开发者, I want Policy Validator 拦截真实 CI 工具, so that Agent 不会在本阶段触发不可控流水线。
42. As an Agent 系统开发者, I want Policy Validator 拦截 MCP/plugin marketplace 相关工具, so that Agent 不能动态扩权。
43. As an Agent 系统开发者, I want Policy Validator 校验步骤顺序, so that 测试任务必须按 API 分析、上下文构建、用例设计、执行、失败分析、报告推进。
44. As an Agent 系统开发者, I want 执行阶段之前禁止 HTTP execution, so that 没有用例和环境准备时不会发请求。
45. As an Agent 系统开发者, I want 失败分析之前必须存在失败执行信号, so that 失败分析不是凭空生成。
46. As an Agent 系统开发者, I want 报告阶段之前必须有足够的任务过程数据, so that 报告内容可信。
47. As an Agent 系统开发者, I want Policy Validator 输出可审计摘要, so that 后续 LlmCallLog、AgentTrace 或执行日志能串联 Planner 与策略结果。
48. As an Agent 系统开发者, I want audit summary 中包含 decisionId, so that 可以追踪某个 PlannerDecision 的命运。
49. As an Agent 系统开发者, I want audit summary 中包含 sourceLlmCallId, so that 可以从策略结果回溯到 LLM 调用。
50. As an Agent 系统开发者, I want audit summary 中包含 proposed tool 和 action, so that 面试讲解时能展示完整决策链。
51. As an Agent 系统开发者, I want reason code 使用枚举建模, so that 测试不会依赖易变文案。
52. As an Agent 系统开发者, I want blocker message 可以被用户阅读, so that 人类知道如何解除阻塞。
53. As an Agent 系统开发者, I want Policy Validator 不调用真实 LLM, so that 单元测试和 CI 稳定快速。
54. As an Agent 系统开发者, I want Policy Validator 不执行真实工具, so that Phase 4 只验证安全边界。
55. As an Agent 系统开发者, I want Fake PlannerDecision 测试覆盖所有关键分支, so that 真实 LLM 接入前策略已经可靠。
56. As an Agent 系统开发者, I want Phase 4 测试覆盖 V1 禁止边界, so that 后续开发不会无意扩展产品范围。
57. As an Agent 系统开发者, I want Phase 4 测试覆盖人工确认状态, so that Human-in-the-loop 的入口有稳定契约。
58. As an Agent 系统开发者, I want Phase 4 测试覆盖 Planner blocked/failed 状态, so that 异常建议不会进入执行层。
59. As an Agent 系统开发者, I want Phase 4 测试覆盖 ToolPolicy 复用路径, so that Phase 2 的工具策略继续作为唯一事实来源。
60. As an Agent 系统开发者, I want Phase 4 测试覆盖顺序错误, so that Agent Loop 不会跳阶段。
61. As an Agent 系统开发者, I want Policy Validator 的 API 保持应用层可调用, so that Phase 5 Replanning Loop 可以直接复用。
62. As an Agent 系统开发者, I want Policy Validator 的结果不依赖数据库事务, so that 规划和策略校验可以作为纯应用流程运行。
63. As an Agent 系统开发者, I want Policy Validator 支持未来持久化审计, so that 后续可以把策略结果写入 Agent Trace。
64. As an Agent 系统开发者, I want Phase 4 形成可讲解的安全架构, so that 面试中能说明 ProbeFlow 如何防止 LLM 失控。

## Implementation Decisions

- 新增 Policy Validator 作为 Controlled Planner 和后续执行层之间的应用层安全门。
- Policy Validator 的主要输入是 PlannerDecision、PlannerInput 和 AgentPolicy。
- Policy Validator 的主要输出是 PolicyValidationResult。
- PolicyValidationResult 至少表达三类状态：允许、需要人工确认、阻塞。
- PolicyValidationResult 必须携带 reason code，测试断言以 reason code 为主，文案为辅。
- PolicyValidationResult 必须携带 blocker 或 message，用于后续展示和 Replanning Loop 消费。
- PolicyValidationResult 必须能生成 audit summary，包含 decisionId、sourceLlmCallId、planner action、proposed tool、validation status、reason code。
- Policy Validator 不直接调用 LLM Provider。
- Policy Validator 不执行 Tool。
- Policy Validator 不修改 Task、PlanStep、TestCase、Memory 或 Report。
- Policy Validator 复用 Phase 2 已有 Tool Contract / AgentPolicy 规则，避免重复维护工具白名单、阶段约束、人工确认规则和 V1 boundary prefix。
- Policy Validator 应优先调用现有 AgentPolicyService 进行工具级校验。
- 若 PlannerDecision 没有 proposed tool，则根据 action 类型决定是否允许、阻塞或要求人工确认。
- 若 PlannerDecision 包含 proposed tool，则必须校验工具名称合法、工具存在、工具出现在当前 available tools 中、工具被 policy 允许、工具阶段匹配。
- 如果工具 contract 要求人类确认，Policy Validator 返回 `REQUIRES_HUMAN_CONFIRMATION`。
- 如果 workflow mode 是 review required，且工具不是只读工具，Policy Validator 返回 `REQUIRES_HUMAN_CONFIRMATION`。
- 如果 PlannerDecision 风险等级为 high 或 critical，Policy Validator 返回人工确认或阻塞，具体规则以 AgentPolicy 当前能力为准。
- 如果 PlannerDecision 置信度低于安全阈值，Policy Validator 不应直接允许执行。
- 阶段顺序校验应该基于 PlannerInput 中的 task state、current phase、last step outcome 和 context bundle summary。
- Phase 4 只实现足够支撑 Agent Core 的顺序规则，不做完整工作流引擎重写。
- V1 边界保持不变：本阶段仍是后端 API 测试 Agent，不扩展 UI 自动化、浏览器自动化、外部工单、外部通知、真实 CI。
- 对禁止能力的拦截必须使用稳定 reason code，而不是只依赖字符串前缀文案。
- PlannerDecision status 为 `BLOCKED` 时，Policy Validator 应输出 blocked validation result，并保留 Planner blockers。
- PlannerDecision status 为 `FAILED` 时，Policy Validator 应输出 blocked validation result，表达 planner failure 不能执行。
- PlannerDecision action 为 `WAIT_FOR_HUMAN` 时，Policy Validator 应输出 human confirmation 或 human input required 状态。
- PlannerDecision action 为 `REPLAN` 时，本阶段不执行工具，只允许作为后续 Replanning Loop 的信号。
- PlannerDecision action 为 `STOP` 时，只允许作为安全终止建议，不允许携带工具执行。
- PlannerDecision action 为 `INSERT_STEP` 时，必须校验 proposed step 的类型、工具和阶段合理性。
- PlannerDecision action 为 `CONTINUE` 时，如果没有新工具建议，可作为继续确定性流程的安全信号。
- 不新增真实外部依赖。
- 不要求接入真实 LLM 作为测试依赖。
- 可以新增领域枚举表达 validation status 和 reason code。
- 可以新增 lightweight request/result record，让 PolicyValidatorService 的调用契约清晰。
- 可以新增 acceptance boundary guard 测试，防止 Phase 4 越界进入 ToolRouter 执行、REST Controller、UI、队列或外部集成。

## Testing Decisions

- 最高测试接缝是 `PolicyValidatorService`。
- 测试应从外部行为出发：输入 PlannerDecision、PlannerInput、AgentPolicy，断言 PolicyValidationResult。
- 测试不要断言私有方法、内部分支名或实现细节。
- 测试优先断言 validation status 和 reason code。
- 文案类 message 可以做轻量断言，但不应成为主要契约。
- 需要覆盖允许路径：安全的 `CONTINUE`、合法阶段内的工具建议、可继续执行的低风险建议。
- 需要覆盖阻塞路径：未知工具、未白名单工具、阶段不匹配工具、V1 禁止边界工具、缺少前置条件、非法 PlannerDecision 状态。
- 需要覆盖人工确认路径：高风险工具、contract 要求确认、review required workflow 下的非只读工具、WAIT_FOR_HUMAN。
- 需要覆盖顺序错误：未完成用例设计时尝试 HTTP 执行、无失败信号时尝试失败分析、信息不足时尝试报告。
- 需要覆盖 Planner 状态：`PROPOSED`、`BLOCKED`、`FAILED`。
- 需要覆盖 Planner action：`CONTINUE`、`INSERT_STEP`、`REPLAN`、`WAIT_FOR_HUMAN`、`STOP`。
- 需要覆盖 audit summary：必须能关联 decisionId、sourceLlmCallId、proposed tool、reason code。
- 需要复用 Phase 2 AgentPolicy / ToolContract 的测试思路，避免重新构造一套不一致的工具规则。
- 需要新增 Phase 4 acceptance boundary guard，确认本阶段没有引入 REST Controller、前端、真实外部 LLM 依赖、真实 ToolRouter 执行、外部通知、工单或 CI 集成。
- 完成后应运行后端完整测试。

## Out of Scope

- 不实现 ToolRouter 的真实执行。
- 不把 Policy Validator 接入完整 Agent Loop 执行闭环。
- 不实现 Replanning Loop。
- 不实现 Human-in-the-loop 的完整交互流程。
- 不实现用户、团队、权限、审批流。
- 不实现 REST API Controller。
- 不实现 Web Console。
- 不实现队列、worker、异步任务执行。
- 不接入 Slack、邮件、Webhook、GitHub issue、Jira 或其他外部通知 / 工单系统。
- 不触发真实 CI。
- 不实现 UI 自动化、浏览器自动化或端到端测试。
- 不允许 LLM 通过自我解释绕过策略。
- 不新增真实 LLM 作为 CI 测试依赖。
- 不重写 V1 确定性编排。
- 不要求新增持久化表；如需审计，先以结构化结果和 audit summary 支撑后续扩展。

## Further Notes

Phase 4 是 V2 Agent Core 的关键安全层。

V2 前三阶段分别解决了：

- Phase 1：LLM 怎么接入、怎么审计。
- Phase 2：Agent 能看到哪些工具、工具有什么边界。
- Phase 3：Planner 怎么生成结构化建议。

Phase 4 要回答的问题是：

```text
Planner 给了建议之后，系统凭什么相信它？
```

答案应该是：

```text
系统不直接相信 Planner。
系统只相信经过 Policy Validator 验证后的 PlannerDecision。
```

这也是 ProbeFlow 面试讲解中的核心亮点之一：它不是“把 LLM 接进来就叫 Agent”，而是用 Java/Spring 的领域模型、策略校验、状态约束和审计结果，把 LLM 建议收束在一个可控、可测、可恢复的工程化 Agent 架构里。
