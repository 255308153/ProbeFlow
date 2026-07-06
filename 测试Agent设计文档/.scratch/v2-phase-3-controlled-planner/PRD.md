状态：ready-for-agent

# V2 Phase 3：Controlled Planner PRD

## Problem Statement

ProbeFlow 已经完成 V1 的确定性后端闭环，也完成了 V2 Agent Core 的前两层基础能力：

```text
V2 Phase 1：LLM Provider 与调用审计
V2 Phase 2：Tool Contract 与 Agent Policy
```

现在系统已经具备两个关键条件：

- 可以通过统一 LLM Provider 调用 fake 或真实模型，并记录 prompt、响应、错误、耗时、token 和审计信息。
- 可以把内部能力以 Tool Contract 暴露给 Agent，并通过 AgentPolicy 给出工具是否允许、是否需要人工确认、是否被禁止的策略结果。

但 ProbeFlow 目前还没有真正的 Planner。V1 的流程仍然主要依赖预设模板和确定性编排，系统还不能基于当前 TaskState、ContextBundle、LastStepOutcome 和可用工具契约生成“下一步应该做什么”的结构化建议。

如果直接让 LLM 参与编排，又会带来几个风险：

- LLM 可能输出自由文本，后续系统无法稳定校验。
- LLM 可能建议不存在的工具、越界工具或错误顺序。
- LLM 可能把“建议”和“执行”混在一起，绕过 Java/Spring 确定性流程。
- LLM 输出可能缺少 confidence、risk level、reasoning、human input 等关键审计字段。
- LLM 输出解析失败时，系统无法给出安全 fallback。
- 面试中无法讲清楚 Agent 如何“决策”，只能说系统接了 LLM 和工具目录。

V2 Phase 3 要解决的问题是：

```text
如何让 ProbeFlow 的 LLM 在严格受控输入和输出协议下，生成可解释、可审计、可校验、但不能直接执行的下一步计划建议？
```

本阶段的核心是 Controlled Planner。Planner 是“建议者”，不是“执行器”。它只产出 `PlanDecision`，不修改 Task，不插入 PlanStep，不调用工具，不触发 HTTP 执行，不写 Memory，不生成 Report。

## Solution

实现 Controlled Planner 基础层，让 ProbeFlow 可以把当前任务状态、上下文摘要、上一步结果、Planner-safe 工具目录和 AgentPolicy 输入给 Planner，并得到结构化 `PlanDecision`。

核心链路：

```text
TaskState
+ ContextBundleSummary
+ LastStepOutcome
+ AvailableToolContracts
+ AgentPolicy
        |
        v
PlannerInput
        |
        v
ControlledPlanner
        |
        v
PlanDecision
```

本阶段建立以下能力：

- 定义 `PlannerInput`，包含任务状态、当前阶段、工作流模式、最近 StepOutcome、上下文摘要、可用工具契约和约束。
- 定义 `PlanDecision`，作为 Planner 的唯一输出对象。
- 定义 `PlannerAction`，至少包含：
  - `CONTINUE`
  - `INSERT_STEP`
  - `REPLAN`
  - `WAIT_FOR_HUMAN`
  - `STOP`
- 定义 `PlannerReasoning` 或等价字段，用于记录 Planner 为什么做出该建议。
- 定义 `PlannerConfidence` 或数值 confidence，用于表达建议可信度。
- 定义 Planner 决策风险等级，与 Tool Contract 风险等级保持兼容。
- 定义 `RequiredHumanInput`，表达 Planner 认为需要人类补充的信息、原因和输入 schema。
- 定义 proposed tool / proposed PlanStep 建议，但本阶段不落库、不执行。
- 实现 `ControlledPlannerService`，作为最高层测试 seam。
- 实现 `FakeControlledPlanner`，用于确定性测试和 CI。
- 实现基于 `LlmApplicationService` 的 LLM planner 调用路径，使用 fake provider 解析结构化输出。
- 实现结构化输出解析与错误归一化：解析失败返回安全的 blocked / failed decision，不执行任何动作。
- Planner 输入必须使用 `PlannerSafeToolCatalogService`，不能直接读取 Java service 或 repository。
- Planner 输出必须能被未来 Policy Validator 消费，但本阶段不实现完整 Policy Validator。

推荐测试 seam：

```text
ControlledPlannerService
```

支撑 seam：

```text
PlannerInputFactory
FakeControlledPlanner
PlanDecisionParser
```

测试应优先从 `ControlledPlannerService.plan(...)` 验证外部行为：给定任务状态、工具目录、上下文摘要和 fake planner/LLM 响应，系统返回稳定结构化决策。不要测试私有 helper，也不要把测试绑定到 prompt 文本细节。

## User Stories

1. As an Agent 系统开发者, I want a Controlled Planner entrypoint, so that Agent planning has one stable application seam.
2. As an Agent 系统开发者, I want PlannerInput to include TaskState, so that Planner decisions are based on the current task reality.
3. As an Agent 系统开发者, I want PlannerInput to include current task phase, so that Planner does not suggest steps from the wrong phase.
4. As an Agent 系统开发者, I want PlannerInput to include workflow mode, so that automatic, semi-automatic and review-required tasks can produce different decisions.
5. As an Agent 系统开发者, I want PlannerInput to include LastStepOutcome, so that Planner can react to success, failure, blockers or paused states.
6. As an Agent 系统开发者, I want PlannerInput to include context summary, so that Planner can reason without receiving unbounded raw data.
7. As an Agent 系统开发者, I want PlannerInput to include available tool contracts, so that Planner can only choose from known tools.
8. As an Agent 系统开发者, I want PlannerInput to include AgentPolicy results, so that Planner knows which tools are allowed, blocked or require confirmation.
9. As an Agent 系统开发者, I want PlannerInput to include constraints, so that prompt and parser can preserve V1/V2 safety boundaries.
10. As an Agent 系统开发者, I want PlanDecision to be structured, so that future Policy Validator can validate it deterministically.
11. As an Agent 系统开发者, I want PlannerAction CONTINUE, so that Planner can suggest continuing the existing plan.
12. As an Agent 系统开发者, I want PlannerAction INSERT_STEP, so that Planner can suggest a missing intermediate step without rewriting everything.
13. As an Agent 系统开发者, I want PlannerAction REPLAN, so that Planner can suggest replacing the remaining plan when state changes.
14. As an Agent 系统开发者, I want PlannerAction WAIT_FOR_HUMAN, so that Planner can identify missing information or risky uncertainty.
15. As an Agent 系统开发者, I want PlannerAction STOP, so that Planner can recommend stopping when the task is complete, unsafe or impossible.
16. As an Agent 系统开发者, I want PlanDecision to include reasoning, so that decisions are explainable during debugging and interviews.
17. As an Agent 系统开发者, I want PlanDecision to include confidence, so that low-confidence decisions can be treated more cautiously later.
18. As an Agent 系统开发者, I want PlanDecision to include risk level, so that risky suggestions can be gated later.
19. As an Agent 系统开发者, I want PlanDecision to include proposed tool name, so that downstream validation can compare it with ToolContractRegistry.
20. As an Agent 系统开发者, I want PlanDecision to include proposed PlanStep type where relevant, so that orchestration can understand future step insertion.
21. As an Agent 系统开发者, I want PlanDecision to include required human input, so that WAIT_FOR_HUMAN is actionable rather than vague.
22. As an Agent 系统开发者, I want required human input to include an input schema, so that future Human-in-the-loop workflow can render or validate it.
23. As an Agent 系统开发者, I want FakeControlledPlanner, so that planning behavior can be tested without real LLM calls.
24. As an Agent 系统开发者, I want fake planner to return deterministic decisions, so that CI remains stable.
25. As an Agent 系统开发者, I want fake planner to simulate malformed output, so that parser fallback behavior is tested.
26. As an Agent 系统开发者, I want LLM planner calls to go through LlmApplicationService, so that all model calls are logged and governed by LlmPolicy.
27. As an Agent 系统开发者, I want Planner prompts to use PromptTemplateRegistry, so that prompt purpose and version are auditable.
28. As an Agent 系统开发者, I want planner LLM output to be parsed as structured data, so that free text does not drive the system.
29. As an Agent 系统开发者, I want parser failures to return safe decisions, so that invalid model output cannot execute anything.
30. As an Agent 系统开发者, I want unknown planner actions to be rejected, so that hallucinated action names are safe.
31. As an Agent 系统开发者, I want unknown tool names to be preserved as invalid decisions, so that future Policy Validator can reject them explicitly.
32. As an Agent 系统开发者, I want Planner to see only PlannerSafeToolView, so that Java service internals stay hidden.
33. As an Agent 系统开发者, I want Planner to avoid direct Java service access, so that intelligence and execution stay separated.
34. As an Agent 系统开发者, I want Planner to avoid direct repository access, so that it cannot reason about database writes.
35. As an Agent 系统开发者, I want Planner to avoid direct HTTP client access, so that it cannot execute API calls.
36. As an Agent 系统开发者, I want Planner decisions to not modify Task, so that Phase 3 remains suggestion-only.
37. As an Agent 系统开发者, I want Planner decisions to not insert PlanSteps, so that execution integration remains out of scope.
38. As an Agent 系统开发者, I want Planner decisions to not call ToolRouter, so that no tool execution happens in this phase.
39. As an Agent 系统开发者, I want Planner decisions to not write Memory, so that Memory Feedback Loop remains a later phase.
40. As an Agent 系统开发者, I want Planner decisions to not generate Reports, so that report generation remains deterministic V1 functionality.
41. As an Agent 系统开发者, I want Planner input to be compact, so that token budget stays predictable.
42. As an Agent 系统开发者, I want Planner input to include citations or context refs rather than full raw documents, so that planning remains auditable.
43. As an Agent 系统开发者, I want Planner to produce a decision id or stable trace id, so that future audit tables can correlate planning decisions.
44. As an Agent 系统开发者, I want Planner decisions to include source LLM call id when LLM-backed, so that planning can be traced back to LLM audit logs.
45. As an Agent 系统开发者, I want Planner decisions to include fake/real provider indicator, so that CI and real planning are distinguishable.
46. As an Agent 系统开发者, I want Planner to support “no-op continue” decisions, so that the existing V1 template can remain primary.
47. As an Agent 系统开发者, I want Planner to support WAIT_FOR_HUMAN for missing environment information, so that the Agent can ask instead of guessing.
48. As an Agent 系统开发者, I want Planner to support WAIT_FOR_HUMAN for high uncertainty, so that low-confidence suggestions do not silently proceed.
49. As an Agent 系统开发者, I want Planner to support STOP for impossible or unsafe tasks, so that later orchestration can terminate cleanly.
50. As an Agent 系统开发者, I want Planner decisions to be serializable, so that future persistence and audit can store them.
51. As an Agent 系统开发者, I want Planner tests to avoid asserting exact prompt wording, so that prompt iteration does not break unrelated tests.
52. As an Agent 系统开发者, I want Planner tests to assert action, tool, risk, confidence and human input behavior, so that external behavior is protected.
53. As an Agent 系统开发者, I want boundary tests proving Phase 3 does not implement Policy Validator, so that phase responsibilities remain clean.
54. As an Agent 系统开发者, I want boundary tests proving Phase 3 does not implement Replanning Loop, so that planning and replanning integration stay separate.
55. As an Agent 系统开发者, I want boundary tests proving Phase 3 does not execute tools, so that Planner remains suggestion-only.
56. As an Agent 系统开发者, I want boundary tests proving Phase 3 does not require real LLM API keys, so that CI remains stable.
57. As a 面试候选人, I want to explain Controlled Planner as the “brain suggestion layer”, so that interviewers see the Agent architecture clearly.
58. As a 面试候选人, I want to explain why Planner sees Tool Contracts instead of services, so that the design shows production safety.
59. As a 面试候选人, I want to explain fake planner and fake LLM testing, so that non-deterministic LLM behavior becomes testable.
60. As a 面试候选人, I want to explain confidence/risk/reasoning fields, so that Agent decisions are auditable rather than magic.
61. As a 面试候选人, I want to explain why Phase 3 still does not execute tools, so that the roadmap demonstrates controlled evolution.

## Implementation Decisions

- 新增 Controlled Planner 基础模块，作为 V2 Agent Core 的第三层能力。
- 最高层入口使用 `ControlledPlannerService` 或等价应用服务。
- 定义 `PlannerInput`，聚合 TaskState、当前阶段、workflow mode、LastStepOutcome、context summary、available tools、policy view 和约束。
- 定义 `TaskState` 或等价 planner-facing task snapshot，不直接把 JPA entity 原样暴露给 Planner。
- 定义 `ContextBundleSummary` 或等价摘要对象，只携带对规划有用的上下文摘要、引用和数量，不携带无限长原文。
- 定义 `LastStepOutcome` planner-facing snapshot，可以由现有 StepOutcome 映射而来。
- Planner 可用工具必须来自 Planner-safe 工具目录视图。
- 定义 `PlanDecision`，作为 Planner 唯一输出。
- 定义 `PlannerAction`，包含 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP。
- 定义 Planner 决策字段：reasoning、confidence、risk level、proposed tool、proposed PlanStep、required human input、blockers、source llm call id、fake provider flag。
- 定义 `RequiredHumanInput`，包含 reason、question、input schema、blocking flag。
- 定义 `ProposedPlanStep` 或等价建议对象，但本阶段不落库。
- 实现 `FakeControlledPlanner`，支持固定决策、按 scenario 返回决策、模拟 malformed output 或 planner failure。
- 实现 LLM-backed planner 路径，必须通过 `LlmApplicationService` 调用，不能直接调用 provider SDK。
- Planner prompt 必须通过 `PromptTemplateRegistry` 管理，至少有 planner purpose 和版本。
- LLM-backed planner 输出必须经过结构化解析，不能直接把自由文本作为决策。
- 解析失败时返回安全失败结果，包含错误类型和 blocker，不执行任何动作。
- Planner 输出中的工具名可以被记录，但本阶段不强制执行工具，也不做完整策略拦截。
- 保持 Planner 与 TaskOrchestrationApplicationService 解耦；本阶段不改变现有 V1 编排默认行为。
- 不新增 PlannerDecision 持久化表；如果需要审计，暂时通过 LLM call log 和返回对象关联。
- 不改造 DefaultPlanStepRunner，不新增真实 ToolRouter 执行。
- 不让 Planner 修改 Task、PlanStep、TestCase、ExecutionRecord、Observation、Memory 或 Report。
- 保持 Java/Spring 主干，不引入 Python 重写。

## Testing Decisions

- 测试应优先通过 `ControlledPlannerService` 验证外部行为，不测试私有 helper。
- 使用 `FakeControlledPlanner` 验证 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP 五种 action。
- 测试 PlannerInput 构建时会包含 task state、current phase、workflow mode、last step outcome、context summary 和 planner-safe tools。
- 测试 PlannerInput 不暴露 Java service、repository、database、HTTP client 或 Spring bean。
- 测试 Planner 只能看到 PlannerSafeToolView，而不是 ToolContract 内部实现或 ApplicationService。
- 测试 fake planner 可以返回确定性决策。
- 测试 fake planner 可以模拟失败或 malformed output。
- 测试 LLM-backed planner 通过 LlmApplicationService 调用，并能关联 llm call id。
- 测试 LLM-backed planner 使用 fake provider 时不需要真实 API key。
- 测试结构化输出解析成功路径，包括 action、tool、risk、confidence、reasoning 和 human input。
- 测试结构化输出解析失败路径，确保返回安全 blocked/failed decision。
- 测试未知 action 不会被当成可执行建议。
- 测试 WAIT_FOR_HUMAN 决策必须包含可行动的人类输入说明。
- 测试低 confidence 或高 risk 决策能被明确表达，而不是被自动执行。
- 测试 PlanDecision 可序列化或可稳定转换为审计摘要。
- 边界测试应证明本阶段没有引入完整 Policy Validator。
- 边界测试应证明本阶段没有引入 ToolRouter 真实执行。
- 边界测试应证明本阶段没有引入 Replanning Loop。
- 边界测试应证明本阶段没有引入 Human-in-the-loop workflow。
- 边界测试应证明本阶段没有引入 Agent Evaluation。
- 边界测试应证明本阶段没有新增 REST Controller、Web Console、queue、worker 或外部集成。
- 边界测试应证明真实 LLM 仍不是 CI 必需依赖。
- 参考 V2 Phase 1 的 LLM 测试和 V2 Phase 2 的 agentpolicy 测试风格。
- 完整后端验证命令仍为 `mvn test`。

## Out of Scope

- 完整 Policy Validator。
- PlannerDecision 持久化审计表。
- ToolRouter 真实执行。
- 修改 TaskOrchestrationApplicationService 默认执行流。
- 自动插入、删除或重排 PlanStep。
- Replanning Loop。
- Human-in-the-loop workflow。
- Agent Memory Feedback Loop。
- Agent Evaluation Harness。
- UI / Web Console。
- REST Controller。
- 队列或后台 worker。
- GitHub、Jira、Slack、webhook 或真实 CI 集成。
- MCP 或插件市场。
- 让 LLM 直接调用工具。
- 让 LLM 直接拿到 Java service、repository、database、HTTP client 或 Spring bean。
- 让真实 LLM 成为 CI 必需依赖。
- Python/pytest runner。

## Further Notes

- V2 Phase 3 是 ProbeFlow 从“有 LLM 基础设施”进入“有 Agent 决策建议”的关键阶段。
- 本阶段最重要的设计边界是：Planner 只建议，不执行。
- Controlled Planner 的输入来自 TaskState、ContextBundle、StepOutcome、ToolContract 和 AgentPolicy，而不是直接读取数据库或调用 service。
- Controlled Planner 的输出是 PlanDecision，而不是直接落库的 PlanStep。
- 后续 V2 Phase 4 可以基于 PlanDecision 实现完整 Policy Validator，负责拒绝越界、乱序、缺少前置条件或需要人工确认的决策。
- 后续 V2 Phase 5 可以把 Planner 接入有限 Replanning Loop，让失败、阻塞和人工反馈真正影响后续执行计划。
- 面试讲解时可以强调：ProbeFlow 的 Agent Brain 是被夹在 LLM Provider 和 Policy/Tool Contract 之间的受控建议层，它可解释、可测试、可审计，但没有越权执行能力。
- 现有未跟踪的 `prd-publication-workflow` scratch 目录与本 PRD 无关，不应修改。
