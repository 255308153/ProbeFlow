状态：draft

# ProbeFlow V2 Agent Core 设计路线

## 1. V2 定位

V1 的目标是完成确定性的后端闭环：

```text
Task 初始化
-> API 分析
-> RAG / Memory 上下文
-> 测试用例生成
-> HTTP 执行
-> 失败分析
-> 结构化报告
-> 任务编排
```

V1 最重要的价值是：即使没有真实 LLM Planner，系统也可以稳定跑完一次 API 测试任务。

V2 的目标需要调整。之前 V2 偏产品化，优先做 REST API、Web Console、团队协作入口。现在项目的核心目标是面试中讲清楚 Agent 设计，因此 V2 应该优先做 Agent Core。

V2 的新定位是：

```text
把 V1 的确定性测试流水线，升级成一个可解释、可控制、可恢复、可评估的垂直 API 测试 Agent。
```

一句话：

```text
V1 证明系统能稳定跑通。
V2 证明这是一个真正工程化设计的 Agent。
```

## 2. 为什么 V2 先做 Agent Core

这个项目最适合在面试中讲的不是“我做了一个接口测试平台”，而是：

```text
我设计了一个面向 API 测试场景的垂直 Agent 系统。
```

面试官真正会关心：

- Agent 如何理解项目？
- Agent 如何构建上下文？
- Agent 如何决定下一步？
- Agent 如何调用工具？
- Agent 如何观察工具结果？
- Agent 如何处理失败？
- Agent 如何等待人类确认？
- Agent 如何沉淀记忆？
- Agent 如何避免 LLM 失控？
- Agent 如何评估自己是否变聪明？

所以 V2 不应该先做 UI 和团队协作。那些是产品化外围，重要但不如 Agent Core 有讲解价值。

V2 应该先把这些 Agent 核心问题做实：

```text
LLM Provider
-> Prompt / CallLog / FakeProvider
-> Tool Contract
-> Controlled Planner
-> Policy Validator
-> Replanning Loop
-> Human-in-the-loop
-> Memory Feedback
-> Agent Evaluation
```

## 3. V2 设计原则

### 3.1 Java/Spring 继续作为核心平台

ProbeFlow 不改成 Python 项目。

原因：

- V1 的领域模型、Repository、ApplicationService、测试和迁移都已经沉淀在 Java/Spring。
- 这个项目要展示的是工程化 Agent 平台，而不是快速脚本工具。
- Java/Spring 更适合表达稳定的领域模型、事务边界、状态机和后端服务化架构。

Python、pytest、embedding worker、rerank worker 后续可以作为插件或外部 worker 接入，但不推翻主干。

### 3.2 先确定性闭环，再接入 LLM

不要一开始就让 LLM 控制全流程。

正确顺序是：

```text
先有确定性模板编排
-> 再接入 LLM Provider
-> 再让 LLM 生成建议
-> 再通过 Policy Validator 校验
-> 再交给 Tool Router 确定性执行
```

这样系统即使没有 LLM，也可以跑通；有 LLM 时，也不会让 LLM 越权。

### 3.3 LLM 只做建议，不直接执行

V2 中 LLM 的定位是“决策建议层”，不是“执行者”。

LLM 可以输出：

- 下一步计划建议。
- 是否需要插入失败分析步骤。
- 是否需要等待用户补充信息。
- 当前上下文缺口。
- 失败原因解释建议。
- 报告摘要润色建议。

LLM 不可以直接：

- 调用数据库。
- 调用 HTTP 执行器。
- 修改 TestCase。
- 修改 Memory。
- 创建外部 ticket。
- 任意选择底层工具。

所有执行必须经过 Java 侧的策略校验和工具路由。

### 3.4 Tool Router 必须确定性

Planner 可以建议下一步 `PlanStepType`，但不能直接调用底层 service。

执行路径必须是：

```text
PlannerDecision
-> PolicyValidator
-> ToolRouter / PlanStepRunner
-> ApplicationService
-> StepOutcome
```

这可以保证 Agent 可控、可测、可审计。

### 3.5 StepOutcome 和 Observation 必须分层

`StepOutcome` 是编排运行时的即时结果，供 Planner 下一轮决策使用。

`Observation` 是失败分析后的持久化结论，进入报告和记忆系统。

两者不能混在一起：

```text
PlanStep 执行
-> StepOutcome
-> Planner 判断下一步
-> 必要时触发 FailureAnalysis
-> Observation
-> Memory / Report
```

这个分层是面试中非常重要的 Agent 架构亮点。

### 3.6 Human-in-the-loop 是可信 Agent 的核心

Agent 不能所有事情都自动做。

V2 要把人类介入点设计成 Agent 状态机的一部分：

- 用例草稿 review。
- 高风险执行确认。
- 缺少环境变量时等待补充。
- Planner 不确定时提出问题。
- 用户采纳或拒绝 Agent 建议。

这些人工反馈后续进入 Memory Feedback Loop。

### 3.7 Agent 必须可评估

V2 不只是“接上 LLM”。V2 还要能回答：

- Planner 选的步骤对不对？
- 工具调用是否符合策略？
- 上下文引用是否有用？
- 失败归因是否准确？
- 生成的用例是否覆盖了目标风险？
- Memory 是否真的改善了下一次任务？

所以 V2 后段必须设计 Agent Evaluation。

## 4. V2 总体架构

V2 Agent Core 的核心链路：

```text
TaskState
+ ContextBundle
+ LastStepOutcome
+ AvailableToolContracts
+ AgentPolicy
        |
        v
ControlledPlanner
        |
        v
PlanDecision
        |
        v
PolicyValidator
        |
        v
PlanStep / ToolRouter
        |
        v
ApplicationService
        |
        v
StepOutcome
        |
        v
Observation / Memory / Report
```

关键思想：

- LLM 不接管系统。
- Planner 不等于执行器。
- Tool Contract 约束可用工具。
- Policy Validator 拦截危险或越界决策。
- StepOutcome 驱动下一轮规划。
- Observation / Memory / Report 负责长期沉淀。

## 5. V2 阶段规划

## V2 Phase 1：LLM Provider 与调用审计

### 目标

把真实 LLM 接入系统，但只作为基础能力，不进入主 Agent Loop。

### 要做什么

- `LlmProvider` 抽象。
- `LlmRequest` / `LlmResponse`。
- `PromptTemplate`。
- `LlmCallLog`。
- Fake LLM Provider，用于 CI 和确定性测试。
- 超时、重试、错误分类。
- token 用量统计。
- 模型名称、temperature、max tokens 等配置。
- Prompt 输入输出结构化约束。

### 不做什么

- 不做真实 Agent Loop。
- 不让 LLM 调工具。
- 不让 LLM 修改数据库。
- 不让 LLM 成为测试必需依赖。

### 完成标准

系统可以安全调用真实或 fake LLM，并完整记录请求、响应、耗时、错误和 token 使用情况。

## V2 Phase 2：Tool Contract 与 Agent Policy

### 目标

把已有应用服务包装成 Agent 可以理解但不能越权调用的工具契约。

### 要做什么

- `ToolContract`。
- `ToolName`。
- `ToolInputSchema`。
- `ToolOutputSchema`。
- 工具前置条件。
- 工具风险等级。
- 工具是否允许自动执行。
- 工具是否需要人工确认。
- `AgentPolicy`。
- 工具白名单。
- 阶段性允许步骤策略。
- V1 边界策略。

### 不做什么

- 不做插件市场。
- 不做外部工具执行。
- 不做 MCP 集成。
- 不让 LLM 直接拿到 Java service 实例。

### 完成标准

每个可被 Agent 规划的能力都有明确工具契约，Planner 只能从白名单工具中提出建议。

## V2 Phase 3：Controlled Planner

### 目标

实现受控 Planner，让 LLM 基于 TaskState、ContextBundle 和 StepOutcome 给出下一步建议。

### 要做什么

- `PlannerInput`。
- `PlanDecision`。
- `PlannerAction`：
  - `CONTINUE`
  - `INSERT_STEP`
  - `REPLAN`
  - `WAIT_FOR_HUMAN`
  - `STOP`
- planner reasoning。
- risk level。
- confidence。
- required human input。
- proposed PlanSteps。
- fake planner 测试。
- LLM planner 结构化输出解析。

### 不做什么

- 不做自由 AutoGPT 式循环。
- 不允许 Planner 直接执行工具。
- 不允许 Planner 绕过模板和 policy。
- 不把 Planner 作为所有任务的必经路径。

### 完成标准

Planner 可以在受控输入下产生结构化决策建议，并被 PolicyValidator 校验。

## V2 Phase 4：Policy Validator 与安全拦截

### 目标

确保 LLM Planner 的建议不会越界、乱序或破坏 V1 边界。

### 要做什么

- `PolicyValidator`。
- step 顺序校验。
- 工具白名单校验。
- 前置条件校验。
- 人工确认要求校验。
- V1 HTTP API 测试边界校验。
- 禁止 UI 自动化、Service 直调、DB 直连断言。
- 禁止外部通知、ticket、真实 CI 操作。
- 拒绝原因结构化。
- PlannerDecision 审计。

### 不做什么

- 不让 LLM 自己解释为什么可以绕过策略。
- 不做权限系统。
- 不做团队审批流。

### 完成标准

任何 PlannerDecision 在执行前都必须通过策略校验，不合法决策会被拒绝并生成可解释 blocker。

## V2 Phase 5：Replanning Loop 与异常恢复

### 目标

把 Planner 接入 V1 编排系统的有限场景，让 Agent 能处理异常、失败、信息不足和人工恢复。

### 要做什么

- `ReplanningTrigger`。
- 支持以下触发场景：
  - PlanStep 失败。
  - 执行 readiness 不足。
  - 失败分析发现高风险。
  - 半自动 review 完成后恢复。
  - 上下文缺失，需要人类补充。
- 支持插入 PlanStep。
- 支持截断剩余计划并重规划。
- 支持等待人类输入。
- 支持保留原模板计划。
- StepOutcome 回流 Planner。

### 不做什么

- 不让 Planner 替代所有模板。
- 不做长期后台自治任务。
- 不做多 Agent 协作。
- 不做分布式任务队列。

### 完成标准

系统在少数明确场景下可以受控重规划，并保持 Task/PlanStep 状态可审计、可恢复。

## V2 Phase 6：Human-in-the-loop Agent Workflow

### 目标

把人类输入变成 Agent 工作流的一部分，而不是 UI 之外的临时操作。

### 要做什么

- Human review request。
- Human decision record。
- waiting reason。
- required input schema。
- draft review feedback。
- blocker resolution。
- high-risk execution approval。
- user rejection reason。
- review 后恢复 Planner。
- 用户反馈进入 MemoryCandidate。

### 不做什么

- 不做完整前端 UI。
- 不做复杂权限。
- 不做多人协同编辑。
- 不做企业审批系统。

### 完成标准

Agent 可以明确进入等待人类状态，消费人类反馈后继续任务，并把反馈沉淀为后续可用的记忆候选。

## V2 Phase 7：Agent Memory Feedback Loop

### 目标

让 Agent 从失败、用户选择、Planner 决策和执行结果中学习。

### 要做什么

- 从 StepOutcome 生成 memory candidate。
- 从用户 review 行为生成 memory candidate。
- 从 Planner 被拒绝的决策中生成 policy learning note。
- 从重复失败中合并 failure pattern。
- Memory confidence 更新。
- Memory dedup / merge。
- Memory usefulness feedback。
- ContextBuilder 在后续任务召回这些经验。

### 不做什么

- 不让 LLM 直接写长期记忆。
- 不做无审计自动覆盖。
- 不删除原始 Observation。

### 完成标准

Agent 的运行经验可以通过 MemoryRefinery 进入长期记忆，并在下一次规划、生成、失败分析中被召回。

## V2 Phase 8：Agent Evaluation Harness

### 目标

建立 Agent 能力评估体系，让项目在面试中能讲清楚“怎么证明 Agent 设计有效”。

### 要做什么

- Planner decision accuracy evaluation。
- Tool selection validity evaluation。
- Context citation usefulness evaluation。
- Failure classification accuracy evaluation。
- Test case coverage evaluation。
- Report usefulness evaluation。
- Memory reuse evaluation。
- Golden task fixtures。
- regression evaluation command。
- evaluation report。

### 不做什么

- 不做商业化大屏。
- 不做复杂 A/B 平台。
- 不追求学术 benchmark。

### 完成标准

可以用固定样例任务评估 Agent 决策、工具选择、上下文使用和记忆复用质量。

## 6. V2 之后的版本路线

### V3：产品化使用层

V3 再做产品化入口：

- REST API。
- Web Console。
- Task 页面。
- Draft Review 页面。
- Report 页面。
- Execution Timeline。

### V4：团队协作与工程集成

V4 做团队使用：

- Project workspace。
- 用户、角色、权限。
- CI/CD。
- GitHub / GitLab / Jira / Slack。
- Webhook。
- 报告导出。
- 质量趋势。

### V5：企业级自治测试智能体

V5 做终极自治：

- 自治测试策略规划。
- 持续发现覆盖缺口。
- 自动维护测试资产。
- 跨服务链路理解。
- 发布前质量门禁。
- 多 Agent 协作。
- 企业安全与治理。

## 7. 面试讲解主线

V2 完成后，项目可以这样讲：

```text
我没有一开始就让 LLM 直接控制测试流程。

我先在 V1 做了一个确定性的 API 测试闭环，保证没有 LLM 也能稳定完成任务。

然后在 V2 引入 LLM Provider、Tool Contract、Controlled Planner 和 Policy Validator。

LLM 只负责提出下一步建议，所有建议必须经过策略校验，再由确定性的 Tool Router 调用后端 ApplicationService。

每一步执行后产生 StepOutcome，进入下一轮规划；如果需要持久分析，再生成 Observation，并进一步进入 Memory 和 Report。

这样 Agent 既具备智能决策能力，又保持可控、可测、可恢复、可审计。
```

这是 ProbeFlow 区别于普通接口测试平台的核心价值。

## 8. V2 非目标

V2 不做以下事情：

- 不重写为 Python。
- 不把 pytest 作为默认核心架构。
- 不推翻 Java/Spring 主干。
- 不先做 Web Console。
- 不先做团队协作和外部集成。
- 不做自由 AutoGPT 式 Agent。
- 不让 LLM 直接调工具或改数据库。
- 不让真实 LLM 成为 CI 必需依赖。
- 不纳入 UI 自动化、Service 直调、DB 直连断言。

## 9. V2 第一个正式 PRD 建议

第一个正式 PRD 应该是：

```text
V2 Phase 1：LLM Provider 与调用审计
```

原因：

- 这是所有后续 Agent 能力的基础。
- 它足够独立，不会破坏 V1 确定性闭环。
- 它可以用 fake provider 做稳定测试。
- 它能在面试中讲清楚“为什么先做 LLM 抽象，而不是直接写 prompt 调接口”。

V2 Phase 1 完成后，再进入：

```text
V2 Phase 2：Tool Contract 与 Agent Policy
V2 Phase 3：Controlled Planner
V2 Phase 4：Policy Validator 与安全拦截
V2 Phase 5：Replanning Loop 与异常恢复
```

这条路线会让 ProbeFlow 的 Agent 设计逐步变得完整，而不是一上来变成不可控的 LLM 自动化脚本。
