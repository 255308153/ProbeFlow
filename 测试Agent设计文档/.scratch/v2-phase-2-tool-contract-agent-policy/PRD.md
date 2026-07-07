状态：ready-for-agent

# V2 Phase 2：Tool Contract 与 Agent Policy PRD

## Problem Statement

ProbeFlow V1 已经完成确定性 API 测试闭环，V2 Phase 1 也已经完成 LLM Provider、Prompt Template、Fake Provider、调用审计、执行策略和日志脱敏。系统现在已经可以安全地把 LLM 作为可审计基础设施接入，但还没有定义 Agent 能“看见什么工具”、能“建议什么动作”、以及“哪些动作允许自动执行”。

如果直接进入 Controlled Planner，会出现几个核心风险：

- Planner 不知道 ProbeFlow 里有哪些能力可以被规划，只能依赖 prompt 中的自然语言描述。
- LLM 可能建议调用不存在的工具、错误的工具，或用错误顺序调用工具。
- LLM 可能绕过 V1 的边界，尝试做 UI 自动化、Service 直调、DB 直连断言、外部通知或 ticket 操作。
- 工具输入输出没有稳定契约，后续结构化 Planner 输出无法可靠校验。
- 高风险工具没有风险等级、前置条件和人工确认要求，Agent 自动执行边界不清晰。
- 面试中无法讲清楚“LLM 为什么不能直接调 service，而必须经过 Tool Contract 和 Agent Policy”。

V2 Phase 2 要解决的问题是：

```text
如何把 V1/V2 已有应用能力包装成 Agent 可理解、可校验、可审计、但不能越权执行的工具契约？
```

本阶段不做真正的 LLM Planner，也不执行 Planner 决策。它只建立 Controlled Planner 之前必须存在的工具目录和策略边界。

## Solution

实现 Tool Contract 与 Agent Policy 基础层，让 ProbeFlow 可以把内部能力以“工具契约”的方式暴露给后续 Planner，同时保持执行权仍在 Java/Spring 确定性编排系统中。

核心链路是：

```text
AgentCapabilityCatalog
-> ToolContractRegistry
-> ToolContract
-> AgentPolicy
-> ToolPolicyDecision
```

本阶段要建立以下能力：

- 定义 `ToolContract`，描述每个 Agent 可规划工具的名称、用途、输入 schema、输出 schema、前置条件、风险等级、自动执行策略和人工确认要求。
- 定义 `ToolName` 或等价稳定工具标识，避免 Planner 使用自由文本猜工具。
- 定义 `ToolInputSchema` 与 `ToolOutputSchema`，用于后续 Planner 输出校验和工具结果解释。
- 定义工具风险等级，例如 LOW、MEDIUM、HIGH、CRITICAL。
- 定义工具自动执行策略，例如允许自动执行、需要人工确认、禁止自动执行。
- 定义工具前置条件，例如必须已有 Task、必须已有 ApiSpec、必须已有 TestCaseDraft、必须已有 ExecutionRecord、必须完成 Review Gate。
- 定义 `AgentPolicy`，表达当前阶段允许哪些工具、哪些工具只允许人工确认后执行、哪些工具永远禁止。
- 定义策略决策对象，返回 ALLOWED、REQUIRES_HUMAN_CONFIRMATION、BLOCKED，以及结构化拒绝原因。
- 建立默认工具目录，把 V1 已有可规划能力以契约形式登记，但不把真实 Java service 实例暴露给 LLM。
- 建立 V1 边界策略，明确禁止 UI 自动化、Service 直调、DB 直连断言、外部通知、ticket 创建、真实 CI 操作和插件市场能力。

推荐测试 seam：

```text
ToolContractRegistry / AgentPolicyService
```

其中最高层测试应验证：给定当前 Task/PlanStep/上下文状态和候选工具名，系统能返回稳定的工具契约和策略判定。测试不应该关心底层类如何组织，只验证 Agent 可以看到的工具目录和策略结果。

## User Stories

1. As an Agent 系统开发者, I want a stable tool catalog, so that Controlled Planner can choose from known tools instead of hallucinating tool names.
2. As an Agent 系统开发者, I want each tool to have a stable ToolName, so that planner output can be validated deterministically.
3. As an Agent 系统开发者, I want each tool to describe its purpose, so that LLM can understand when to suggest it.
4. As an Agent 系统开发者, I want each tool to declare input schema, so that invalid planner arguments can be rejected before execution.
5. As an Agent 系统开发者, I want each tool to declare output schema, so that StepOutcome and planner context can interpret tool results consistently.
6. As an Agent 系统开发者, I want each tool to declare preconditions, so that Agent cannot run steps before required state exists.
7. As an Agent 系统开发者, I want each tool to declare risk level, so that high-risk actions are never treated like read-only analysis.
8. As an Agent 系统开发者, I want each tool to declare whether it allows automatic execution, so that future planner decisions can be safely gated.
9. As an Agent 系统开发者, I want each tool to declare whether human confirmation is required, so that risky operations enter a review path.
10. As an Agent 系统开发者, I want AgentPolicy to maintain a whitelist of allowed tools, so that LLM cannot invent or call unregistered tools.
11. As an Agent 系统开发者, I want AgentPolicy to block tools outside the current phase, so that V2 evolves without accidentally enabling future capabilities.
12. As an Agent 系统开发者, I want AgentPolicy to return structured blocker reasons, so that future UI/reporting can explain why a decision was rejected.
13. As an Agent 系统开发者, I want a policy result for human confirmation, so that blocked and review-required actions are not conflated.
14. As an Agent 系统开发者, I want the tool catalog to include API analysis, so that future Planner can reason about source parsing as a tool.
15. As an Agent 系统开发者, I want the tool catalog to include knowledge retrieval, so that future Planner can request relevant RAG context.
16. As an Agent 系统开发者, I want the tool catalog to include memory context building, so that future Planner can use task/session/long-term memory.
17. As an Agent 系统开发者, I want the tool catalog to include test case generation, so that future Planner can suggest draft generation.
18. As an Agent 系统开发者, I want the tool catalog to include draft review gate semantics, so that semi-automatic workflows remain controlled.
19. As an Agent 系统开发者, I want the tool catalog to include HTTP execution as high risk, so that real API calls require explicit readiness and policy checks.
20. As an Agent 系统开发者, I want the tool catalog to include failure analysis, so that failed execution can be analyzed in a controlled loop.
21. As an Agent 系统开发者, I want the tool catalog to include report generation, so that final task output remains an explicit tool capability.
22. As an Agent 系统开发者, I want the tool catalog to include LLM call capabilities only as support tools, so that LLM is not treated as an executor.
23. As an Agent 系统开发者, I want tools to be grouped by capability area, so that planner prompts can present them clearly.
24. As an Agent 系统开发者, I want read-only tools and mutating tools to be separated, so that Agent Policy can treat them differently.
25. As an Agent 系统开发者, I want tool contracts to avoid Java implementation details, so that LLM sees stable public contracts rather than service internals.
26. As an Agent 系统开发者, I want tool contracts to avoid exposing repository names, so that planner cannot reason about direct database access.
27. As an Agent 系统开发者, I want policy to forbid UI automation, so that ProbeFlow remains focused on backend API testing.
28. As an Agent 系统开发者, I want policy to forbid service direct-call assertions, so that tests remain black-box HTTP/API oriented.
29. As an Agent 系统开发者, I want policy to forbid DB direct assertions as an Agent tool, so that V1 testing boundary stays stable.
30. As an Agent 系统开发者, I want policy to forbid external notification tools, so that V2 does not accidentally become an integration platform.
31. As an Agent 系统开发者, I want policy to forbid GitHub/Jira/Slack ticket creation, so that team workflow stays out of this phase.
32. As an Agent 系统开发者, I want policy to forbid real CI operations, so that Agent cannot trigger external deployments or pipelines.
33. As an Agent 系统开发者, I want unregistered tool names to be rejected, so that hallucinated planner output is safe.
34. As an Agent 系统开发者, I want schema-invalid tool input to be rejected, so that malformed planner output does not reach execution.
35. As an Agent 系统开发者, I want missing preconditions to be rejected, so that tools run only when the task state is ready.
36. As an Agent 系统开发者, I want policy decisions to be deterministic, so that tests and CI can verify Agent safety.
37. As an Agent 系统开发者, I want policy decisions to be independent of real LLM calls, so that this phase remains testable without API keys.
38. As an Agent 系统开发者, I want tool contracts to be serializable to planner context, so that future Planner can receive only safe metadata.
39. As an Agent 系统开发者, I want tool contracts to include concise descriptions, so that future prompt templates remain compact.
40. As an Agent 系统开发者, I want tool contracts to include examples of valid input shape, so that future structured output parsing is easier.
41. As an Agent 系统开发者, I want tool contracts to include expected output summary, so that future planner can reason over StepOutcome.
42. As an Agent 系统开发者, I want AgentPolicy to support workflow mode, so that automatic, semi-automatic, and review-heavy tasks can allow different tools.
43. As an Agent 系统开发者, I want AgentPolicy to support current task phase, so that different PlanStep stages can have different allowed actions.
44. As an Agent 系统开发者, I want AgentPolicy to support V1 boundary rules, so that legacy deterministic behavior is protected.
45. As an Agent 系统开发者, I want policy decisions to be auditable values, so that future PlannerDecision logs can record allow/block reasons.
46. As an Agent 系统开发者, I want the implementation to avoid invoking actual tool execution, so that Phase 2 only defines contracts and policy.
47. As an Agent 系统开发者, I want this phase to avoid building a full ToolRouter, so that execution integration stays in a later phase.
48. As an Agent 系统开发者, I want this phase to avoid a real Planner, so that tool policy can be tested before LLM decisions exist.
49. As an Agent 系统开发者, I want boundary tests proving LLM cannot access Java service instances, so that Agent execution stays mediated.
50. As an Agent 系统开发者, I want boundary tests proving no external tools are registered, so that phase scope remains narrow.
51. As an Agent 系统开发者, I want boundary tests proving no MCP/plugin marketplace behavior exists, so that tool contracts remain internal.
52. As an Agent 系统开发者, I want boundary tests proving no REST/frontend/queue work was added, so that Agent Core remains the focus.
53. As a 面试候选人, I want to explain Tool Contract as the boundary between intelligence and execution, so that interviewers see the system is controlled.
54. As a 面试候选人, I want to explain Agent Policy as the safety layer before ToolRouter, so that the project is not just prompt engineering.
55. As a 面试候选人, I want to explain why LLM sees contracts but not services, so that the architecture shows production-grade judgment.
56. As a 面试候选人, I want to explain read-only, mutating, and high-risk tool categories, so that Agent safety design is concrete.
57. As a 面试候选人, I want to explain how Phase 2 enables Controlled Planner in Phase 3, so that the roadmap feels intentional.

## Implementation Decisions

- 新增 Agent Tool Contract 基础模块，作为 V2 Agent Core 的第二层能力。
- 最高层能力以 `ToolContractRegistry`、`AgentPolicyService` 或等价应用服务表达。
- 定义稳定 `ToolName`，避免自由字符串成为主要内部协议。
- 定义 `ToolContract`，包含工具名称、能力分组、描述、输入 schema、输出 schema、前置条件、风险等级、自动执行策略、人工确认要求和标签。
- 定义 `ToolInputSchema` 与 `ToolOutputSchema`，本阶段可以使用轻量结构化描述，不要求完整 JSON Schema 引擎。
- 定义 `ToolRiskLevel`，至少覆盖 LOW、MEDIUM、HIGH、CRITICAL。
- 定义 `ToolExecutionMode` 或等价枚举，表达 AUTO_ALLOWED、HUMAN_CONFIRMATION_REQUIRED、BLOCKED。
- 定义 `ToolPrecondition`，表达 Task、ApiSpec、ContextBundle、TestCaseDraft、ExecutionRecord、Observation、Report 等状态依赖。
- 定义 `AgentPolicy`，表达工具白名单、阶段性允许策略、工作流模式策略和 V1 边界策略。
- 定义 `ToolPolicyDecision`，返回 ALLOWED、REQUIRES_HUMAN_CONFIRMATION、BLOCKED，以及可读 reason code 和 message。
- 默认工具目录只登记 ProbeFlow 内部已有能力，不登记外部工具。
- 工具目录面向 Planner 暴露的是契约和描述，不暴露 Java service、repository、entity manager、HTTP client 或数据库连接。
- API analysis 工具应被视为中低风险分析工具，要求有 SourceMaterial 或等价输入。
- Knowledge retrieval 与 Memory context 工具应被视为只读上下文工具，默认可以自动执行。
- Test case generation 工具应被视为生成草稿工具，输出仍需 review gate 控制。
- HTTP execution 工具应被视为高风险工具，要求 readiness、已审核用例和策略允许。
- Failure analysis 工具应在已有 execution result 或 failure signal 时允许执行。
- Report generation 工具应在任务有足够过程数据后允许执行。
- LLM call 能力不作为自由工具暴露给 Planner；它是 Planner 或解释模块的基础设施能力。
- 本阶段不改造现有 `DefaultPlanStepRunner` 的执行行为，避免把契约层和执行层混在一起。
- 本阶段不实现真实 ToolRouter；只保证后续 ToolRouter 可以消费这些契约和 policy decision。
- 本阶段不实现 Controlled Planner；只保证 Planner 未来只能从该 registry 获取工具列表。
- 本阶段不引入 REST Controller、前端、队列、后台 worker、外部集成或插件市场。
- 保持 Java/Spring 主干，不引入 Python 重写。

## Testing Decisions

- 测试应优先验证 Agent 能看到的外部行为：工具目录内容、工具契约字段、策略判定结果、边界禁止规则。
- 不测试私有 helper，不把测试绑定到 registry 的内部集合实现。
- `ToolContractRegistry` 测试应验证所有 V2 Phase 2 允许的内部工具都存在稳定名称和完整契约。
- `ToolContractRegistry` 测试应验证每个工具都有输入 schema、输出 schema、风险等级、自动执行策略和前置条件。
- `AgentPolicyService` 测试应验证白名单工具可以返回 ALLOWED 或 REQUIRES_HUMAN_CONFIRMATION。
- `AgentPolicyService` 测试应验证未注册工具返回 BLOCKED。
- `AgentPolicyService` 测试应验证 schema 缺失或输入不合法时返回 BLOCKED。
- `AgentPolicyService` 测试应验证前置条件不满足时返回 BLOCKED。
- `AgentPolicyService` 测试应验证高风险工具在缺少人工确认或 readiness 时不会自动允许。
- 测试应覆盖 automatic、semi-automatic、review-required 等不同 workflow mode。
- 测试应覆盖 API analysis、knowledge retrieval、memory context、test case generation、HTTP execution、failure analysis、report generation 等内部能力。
- 测试应覆盖禁止工具：UI automation、service direct call、DB direct assertion、external notification、ticket creation、real CI operation、MCP/plugin marketplace。
- 边界测试应证明本阶段没有引入 Controlled Planner、Replanning Loop、Human-in-the-loop workflow、Agent Evaluation、REST Controller、frontend、queue、worker 或外部集成。
- 边界测试应证明 LLM/Planner 不会获得 Java service 实例。
- 边界测试应证明真实 LLM 仍不是 CI 必需依赖。
- 参考 V2 Phase 1 的 acceptance boundary guard 风格新增 V2 Phase 2 boundary guard。
- 完整后端验证命令仍为 `mvn test`。

## Out of Scope

- Controlled Planner。
- LLM planner 结构化输出解析。
- PlannerDecision 审计表。
- Policy Validator 完整执行链。
- ToolRouter 真实执行。
- Replanning Loop。
- Human-in-the-loop workflow。
- Agent Evaluation。
- 插件市场。
- MCP 集成。
- 外部工具执行。
- GitHub、Jira、Slack、邮件或 webhook 集成。
- REST Controller。
- Web Console。
- 队列或后台 worker。
- 权限系统、多租户和团队审批流。
- Python/pytest runner。
- 真实 LLM 成为 CI 必需依赖。
- 让 LLM 直接拿到 Java service、repository、database、HTTP client 或 Spring bean。

## Further Notes

- V2 Phase 2 是 Controlled Planner 的前置阶段。没有 Tool Contract 和 Agent Policy，Planner 就只能靠自然语言猜系统能力，风险过高。
- 本阶段最重要的面试讲点是：ProbeFlow 把 LLM 智能层和 Java 执行层隔开，中间用 Tool Contract 表达可见能力，用 Agent Policy 表达安全边界。
- Tool Contract 不是执行器，Agent Policy 也不是完整 Policy Validator。它们是后续 Planner、Policy Validator、ToolRouter 共享的协议地基。
- 后续 V2 Phase 3 可以基于本阶段工具目录实现 Controlled Planner，让 LLM 只从白名单工具中提出结构化决策建议。
- 后续 V2 Phase 4 可以把 AgentPolicy 扩展为完整 Policy Validator，在执行前拦截越界、乱序、高风险和缺少人工确认的 PlannerDecision。
- 现有未跟踪的 `prd-publication-workflow` scratch 目录与本 PRD 无关，不应修改。
