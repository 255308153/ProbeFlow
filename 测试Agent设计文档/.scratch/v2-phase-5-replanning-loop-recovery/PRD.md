状态：ready-for-agent

# ProbeFlow V2 Phase 5：Replanning Loop 与异常恢复 PRD

## Problem Statement

ProbeFlow V2 已经完成了 LLM Provider、Tool Contract / AgentPolicy、Controlled Planner 和 Policy Validator。系统现在可以让 Planner 基于任务上下文产出结构化 `PlanDecision`，也可以在执行前用策略层判断建议是否允许、是否需要人工确认、是否必须拦截。

但目前 Agent 还缺少真正的“恢复能力”。

V1 的确定性编排在遇到失败时，主要行为是把当前 PlanStep 标记为失败、跳过后续步骤、把 Task 标记为失败。这保证了系统稳定，但不够像 Agent。因为一个工程化 Agent 不应该只会顺序执行模板，它还应该能在有限场景下根据执行结果做出恢复决策：

- 某个 PlanStep 失败后，是否需要插入失败分析步骤？
- HTTP 执行 readiness 不足时，是否应该等待人类补充环境变量或配置？
- 失败分析发现高风险时，是否应该截断剩余计划并生成报告？
- 人工 review 完成后，是否应该恢复执行被暂停的计划？
- 上下文缺失时，是否应该先补充 RAG / Memory 检索再继续用例生成？
- Planner 建议重规划后，系统如何保证不会破坏已完成步骤和任务审计？

现在 Phase 4 解决的是“Planner 建议能不能被信任”。Phase 5 要解决的是“可信建议如何在异常场景下安全改变任务计划”。

从用户视角看，ProbeFlow 的 Agent Core 需要开始具备可恢复性：

```text
StepOutcome 出现失败或阻塞
-> 构造 PlannerInput
-> ControlledPlanner 给出恢复建议
-> PolicyValidator 校验建议
-> Replanning Loop 受控修改计划或等待人类
-> 任务可以继续、暂停或安全失败
```

这不是自由 AutoGPT 式循环。Phase 5 必须保持一个关键原则：

```text
Agent 只能在明确触发器下做一次受控重规划，不能无限自我驱动。
```

## Solution

新增 Replanning Loop 与异常恢复能力，作为 V1 编排系统和 V2 Agent Core 之间的受控连接层。

目标链路是：

```text
Task / PlanStep / StepOutcome
-> ReplanningTrigger
-> ReplanningApplicationService
-> PlannerInput
-> ControlledPlanner
-> PolicyValidator
-> ReplanningResult
-> 受控计划变更 / 等待人类 / 保留模板 / 安全失败
```

Phase 5 的核心输出不是“让 Agent 自动跑完整任务”，而是新增一个可测试、可审计、可恢复的重规划入口：

- 明确哪些场景可以触发重规划。
- 明确一次重规划只能消费一个触发器。
- 明确 PlannerDecision 必须经过 PolicyValidator。
- 明确计划变更只能影响 pending / skipped / failed 后的安全范围。
- 明确成功历史不可被改写。
- 明确等待人类只是进入暂停状态，不实现完整 Human-in-the-loop 产品流程。
- 明确所有重规划尝试都要留下结构化结果和审计摘要。

建议的最高测试接缝是 `ReplanningApplicationService` 或等价应用层服务。测试应从外部行为出发，构造 Task、PlanStep、StepOutcome、ReplanningTrigger 和 fake planner 决策，断言最终 ReplanningResult、Task 状态、PlanStep 状态/顺序、blockers 和审计摘要。

Phase 5 不应该把测试拆散到 Planner、PolicyValidator、PlanStepRunner 的内部私有方法。Planner 和 Policy 已经在前序阶段测试过；本阶段重点测试“重规划入口如何组合这些能力并安全改变计划”。

## User Stories

1. As an Agent 系统开发者, I want a Replanning Loop entrypoint, so that 任务异常时可以受控进入恢复流程。
2. As an Agent 系统开发者, I want explicit ReplanningTrigger values, so that 只有明确场景可以触发重规划。
3. As an Agent 系统开发者, I want PlanStep failure to trigger replanning, so that Agent 不只是失败后直接停止。
4. As an Agent 系统开发者, I want execution readiness blockers to trigger replanning, so that 缺少环境或输入时可以等待人类补充。
5. As an Agent 系统开发者, I want high-risk failure analysis to trigger replanning, so that Agent 能在风险升高时改变后续计划。
6. As an Agent 系统开发者, I want review completion to trigger recovery, so that 半自动 review 后任务可以继续。
7. As an Agent 系统开发者, I want missing context to trigger replanning, so that Agent 可以先补上下文再继续设计测试。
8. As an Agent 系统开发者, I want StepOutcome to flow into PlannerInput, so that Planner 能看到最新失败、阻塞和结果引用。
9. As an Agent 系统开发者, I want last failed step details in PlannerInput, so that Planner 能针对失败步骤提出恢复建议。
10. As an Agent 系统开发者, I want blocker details in PlannerInput, so that Planner 不需要猜测为什么任务停下。
11. As an Agent 系统开发者, I want result refs in PlannerInput, so that Planner 可以基于 API spec、case、execution 或 report 引用决策。
12. As an Agent 系统开发者, I want existing task state to be preserved in PlannerInput, so that 重规划不是脱离当前任务的孤立判断。
13. As an Agent 系统开发者, I want available tool contracts to still constrain Planner, so that 重规划时不能扩权。
14. As an Agent 系统开发者, I want AgentPolicy to still apply during replanning, so that 恢复路径和正常路径一样受控。
15. As an Agent 系统开发者, I want every PlannerDecision to pass PolicyValidator before plan mutation, so that LLM 不能直接改计划。
16. As an Agent 系统开发者, I want policy-blocked decisions to stop replanning safely, so that 被拒绝的建议不会影响 Task 和 PlanStep。
17. As an Agent 系统开发者, I want policy human-confirmation decisions to pause task safely, so that 高风险恢复动作不会自动执行。
18. As an Agent 系统开发者, I want safe CONTINUE decisions to keep the template plan, so that 不需要重规划时系统不做多余变更。
19. As an Agent 系统开发者, I want safe REPLAN decisions to become structured recovery results, so that 后续可以明确执行截断或插入策略。
20. As an Agent 系统开发者, I want safe INSERT_STEP decisions to create pending PlanStep, so that Agent 可以补充恢复步骤。
21. As an Agent 系统开发者, I want inserted PlanStep to have deterministic step order, so that 后续编排可以稳定按顺序执行。
22. As an Agent 系统开发者, I want inserted PlanStep to keep a goal and input ref, so that 恢复步骤有可读意图和来源。
23. As an Agent 系统开发者, I want inserted PlanStep to be traceable to decision id, so that 可以解释它为什么出现。
24. As an Agent 系统开发者, I want successful historical steps to never be rewritten, so that 审计历史可信。
25. As an Agent 系统开发者, I want pending downstream steps to be skippable during truncate-and-replan, so that 错误路径不会继续执行。
26. As an Agent 系统开发者, I want running steps not to be mutated by replanning, so that 并发状态不会被破坏。
27. As an Agent 系统开发者, I want failed steps to remain failed unless explicitly retried, so that 失败事实不被抹掉。
28. As an Agent 系统开发者, I want retry count to be respected, so that Agent 不会无限重试同一个步骤。
29. As an Agent 系统开发者, I want maximum replanning attempts per task, so that 任务不会进入无限循环。
30. As an Agent 系统开发者, I want maximum replanning attempts per trigger, so that 同一个失败不会反复插入恢复步骤。
31. As an Agent 系统开发者, I want idempotent trigger handling, so that 重复调用同一个 recovery request 不会重复插入步骤。
32. As an Agent 系统开发者, I want ReplanningResult to describe what changed, so that 调用方能知道计划是否被修改。
33. As an Agent 系统开发者, I want ReplanningResult to include validation result, so that 调用方能知道 Planner 建议是否被 policy 接受。
34. As an Agent 系统开发者, I want ReplanningResult to include decision audit summary, so that 后续 Agent Trace 可以串起完整链路。
35. As an Agent 系统开发者, I want ReplanningResult to include inserted step ids, so that 后续编排可以定位新步骤。
36. As an Agent 系统开发者, I want ReplanningResult to include skipped step ids, so that 计划截断是可审计的。
37. As an Agent 系统开发者, I want ReplanningResult to include blockers, so that 任务无法恢复时原因明确。
38. As an Agent 系统开发者, I want WAIT_FOR_HUMAN decisions to pause without full UI workflow, so that Phase 5 能表达等待能力但不越界到 Phase 6。
39. As an Agent 系统开发者, I want human input request details to be stored in task metadata or equivalent audit state, so that 后续 Phase 6 可以继续消费。
40. As an Agent 系统开发者, I want review-completed trigger to resume paused plans, so that 半自动 review 后任务可以继续。
41. As an Agent 系统开发者, I want failed execution readiness to produce human-readable blockers, so that 用户知道要补哪个环境或参数。
42. As an Agent 系统开发者, I want missing context recovery to insert knowledge retrieval before test generation, so that RAG 缺口可以被补齐。
43. As an Agent 系统开发者, I want failure analysis recovery to insert analysis before report, so that 报告基于分析结果而不是直接失败。
44. As an Agent 系统开发者, I want high-risk findings to stop or pause downstream execution, so that 风险不会被后续步骤掩盖。
45. As an Agent 系统开发者, I want template plan preservation to be a valid recovery result, so that Planner 不需要每次都改计划。
46. As an Agent 系统开发者, I want no-op replanning to be explicit, so that 审计中能看到系统选择不变更计划。
47. As an Agent 系统开发者, I want blocked replanning to leave plan unchanged, so that 不安全建议不会污染任务计划。
48. As an Agent 系统开发者, I want rejected planner decisions to be visible in result, so that 面试中能展示防失控链路。
49. As an Agent 系统开发者, I want fake planner scenarios for recovery, so that CI 不依赖真实 LLM。
50. As an Agent 系统开发者, I want deterministic tests for insert-step recovery, so that 计划变更行为可重复。
51. As an Agent 系统开发者, I want deterministic tests for truncate-and-replan recovery, so that 下游步骤处理规则稳定。
52. As an Agent 系统开发者, I want deterministic tests for wait-for-human recovery, so that 暂停状态和 blockers 稳定。
53. As an Agent 系统开发者, I want deterministic tests for policy rejection, so that 被拦截建议不会改计划。
54. As an Agent 系统开发者, I want deterministic tests for idempotency, so that 重复触发不会重复变更。
55. As an Agent 系统开发者, I want TaskOrchestration integration to remain limited, so that V1 确定性闭环不会被重写。
56. As an Agent 系统开发者, I want existing PlanStepRunner behavior to remain the executor, so that Phase 5 不引入新工具执行器。
57. As an Agent 系统开发者, I want existing ManualReviewGate behavior to remain compatible, so that 半自动 review 的 V1 能力不被破坏。
58. As an Agent 系统开发者, I want completed tasks not to be replanned, so that 历史任务不会被误修改。
59. As an Agent 系统开发者, I want cancelled tasks not to be replanned, so that 用户取消意图优先。
60. As an Agent 系统开发者, I want failed tasks to be recoverable only through explicit trigger, so that 系统不会偷偷复活任务。
61. As an Agent 系统开发者, I want all plan mutations to be transactional, so that Task 和 PlanStep 不会出现半更新。
62. As an Agent 系统开发者, I want replan audit metadata to include trigger type, so that 可以知道为什么触发恢复。
63. As an Agent 系统开发者, I want replan audit metadata to include previous step id, so that 可以定位恢复来源。
64. As an Agent 系统开发者, I want replan audit metadata to include policy status, so that 可以证明策略门生效。
65. As an Agent 系统开发者, I want replan audit metadata to include attempt count, so that 可以排查循环保护。
66. As an Agent 系统开发者, I want no background daemon in Phase 5, so that 重规划只在显式调用下发生。
67. As an Agent 系统开发者, I want no distributed queue in Phase 5, so that 架构保持简单可测。
68. As an Agent 系统开发者, I want no multi-agent coordination in Phase 5, so that 单 Agent 恢复路径先稳定。
69. As an Agent 系统开发者, I want no external ticket or notification side effects, so that 恢复逻辑不越界到集成平台。
70. As an Agent 系统开发者, I want Phase 5 to be explainable in interviews, so that 可以讲清楚 Agent 如何在失败后恢复而不是失控。

## Implementation Decisions

- 新增 Replanning Loop 作为 V2 Agent Core 的受控恢复层。
- Replanning Loop 位于 TaskOrchestrationApplicationService、ControlledPlannerService 和 PolicyValidatorService 之间。
- Phase 5 的最高应用层入口建议是 ReplanningApplicationService 或等价服务。
- ReplanningApplicationService 的输入应包含 task id、trigger、相关 PlanStep、最新 StepOutcome 或等价结果摘要、可选的人类输入 / review 完成信号。
- 新增 ReplanningTrigger 或等价枚举，至少覆盖 PlanStep failed、execution readiness missing、failure analysis high risk、review completed、context missing、human input required。
- ReplanningTrigger 必须是白名单，不允许任意字符串触发 Agent 重规划。
- ReplanningApplicationService 每次只处理一个 trigger。
- 每个 trigger 默认只做一次 planner call 和一次 policy validation。
- 如需允许多次尝试，必须有明确最大 attempt 限制。
- StepOutcome 必须回流到 PlannerInput，作为 last step outcome / blockers / result refs 的来源。
- PlannerInput 应复用 Phase 3 已有构建能力，不重新发明一套上下文格式。
- PlannerDecision 必须经过 PolicyValidatorService 后才能应用任何计划变更。
- PolicyValidationResult 为 BLOCKED 时，不允许修改 Task 或 PlanStep。
- PolicyValidationResult 为 REQUIRES_HUMAN_CONFIRMATION 时，允许把 Task 置为等待状态并记录 required human input，但不执行工具。
- PolicyValidationResult 为 ALLOWED 时，才允许应用有限的计划变更。
- 支持的计划变更类型应保持有限：keep template、insert step、truncate downstream and append recovery step、wait for human、safe stop。
- CONTINUE decision 默认映射为 keep template / resume，不应无故改计划。
- INSERT_STEP decision 可以插入一个 pending PlanStep，但必须保留稳定 step order、goal、inputRef 或等价来源信息。
- REPLAN decision 可以截断 pending downstream steps，并追加受控恢复步骤；不允许重写成功历史。
- WAIT_FOR_HUMAN decision 暂停任务并记录 required human input；完整人机交互在 Phase 6。
- STOP decision 可以安全停止或保留失败状态，但不能执行工具。
- 成功的 PlanStep 不允许被重写、删除或改成 pending。
- RUNNING 状态的 PlanStep 不允许被重规划直接修改。
- FAILED 状态的 PlanStep 不应被抹除；如需要 retry，必须通过 retry count 和显式恢复步骤表达。
- Pending downstream steps 可以在截断时标记为 skipped。
- 计划插入应避免重复插入；可以使用 trigger key、decision id、source step id 或 task metadata 做幂等保护。
- Task metadata 可以作为 Phase 5 的轻量审计载体，记录 replan trigger、decision id、policy status、attempt count、inserted/skipped step ids 和 blockers。
- 不强制新增持久化表；如果现有 Task metadata 无法表达最小审计，再考虑新增轻量表。
- ReplanningResult 应是调用方的主要返回契约，包含 status、trigger、decision audit summary、policy validation summary、plan mutation summary、inserted steps、skipped steps、blockers。
- ReplanningResult 的状态建议包含 APPLIED、NOOP、WAITING_FOR_HUMAN、REJECTED_BY_POLICY、NOT_TRIGGERABLE、FAILED。
- ReplanningApplicationService 应在事务边界内应用计划变更，避免 Task 和 PlanStep 半更新。
- TaskOrchestrationApplicationService 可以在明确失败或暂停点调用 ReplanningApplicationService，但不应被重写成自由 Agent loop。
- Phase 5 不新增新的 ToolRouter，也不替代现有 PlanStepRunner。
- Phase 5 不要求真实 LLM；fake planner 必须能覆盖恢复场景。
- Phase 5 不实现完整 Human-in-the-loop workflow，只留下等待状态、required human input 和恢复触发入口。
- Phase 5 不引入 REST Controller、Web Console、queue、worker 或外部集成。
- Phase 5 不允许 Planner 直接修改数据库；所有状态变更都在 Java 应用层受控完成。

## Testing Decisions

- 最高测试接缝是 ReplanningApplicationService 或等价应用层服务。
- 测试应只断言外部行为：ReplanningResult、Task 状态、PlanStep 状态/顺序、blockers、audit summary。
- 测试不要断言私有方法、内部 helper 顺序或具体实现细节。
- 使用 fake ControlledPlanner 场景覆盖 recovery decisions，避免真实 LLM 成为 CI 依赖。
- 使用真实或测试配置的 PolicyValidatorService 覆盖策略通过、策略拒绝、人工确认三类结果。
- 需要覆盖 PlanStep failed 触发 insert failure analysis。
- 需要覆盖 execution readiness missing 触发 wait for human。
- 需要覆盖 missing context 触发 retrieve knowledge。
- 需要覆盖 high-risk failure analysis 触发 truncate downstream / safe stop。
- 需要覆盖 review completed 触发 resume / keep template。
- 需要覆盖 policy blocked decision 不改 Task 和 PlanStep。
- 需要覆盖 requires human confirmation 的暂停结果。
- 需要覆盖 CONTINUE decision 的 no-op / keep template 行为。
- 需要覆盖 INSERT_STEP decision 的 step order、pending 状态、goal、input ref 和 audit 信息。
- 需要覆盖 REPLAN decision 的 downstream pending steps skipped 和恢复步骤追加。
- 需要覆盖 STOP decision 不携带工具执行。
- 需要覆盖 completed task 不可重规划。
- 需要覆盖 cancelled task 不可重规划。
- 需要覆盖重复 trigger 的幂等性。
- 需要覆盖最大 attempt 限制，防止无限循环。
- 需要覆盖事务性计划变更，确保失败时没有半更新。
- 需要新增 V2 Phase 5 acceptance boundary guard，证明本阶段没有实现自由 AutoGPT loop、后台自治任务、分布式队列、多 Agent、完整 HITL UI、REST/frontend 或外部集成。
- 完成后应运行后端完整测试。

## Out of Scope

- 不做自由 AutoGPT 式循环。
- 不让 Planner 替代所有模板计划。
- 不把 Planner 作为所有任务的必经路径。
- 不实现长期后台自治任务。
- 不实现分布式任务队列。
- 不实现多 Agent 协作。
- 不实现完整 Human-in-the-loop workflow。
- 不实现前端 review 页面或人工输入 UI。
- 不实现 REST Controller。
- 不实现 Web Console。
- 不实现权限、团队审批或多人协作。
- 不接入 Slack、Jira、GitHub issue、邮件、Webhook 或真实 CI。
- 不实现 UI 自动化、浏览器自动化或端到端测试。
- 不让 LLM 直接调用工具、修改数据库或绕过 PolicyValidator。
- 不要求真实 LLM 作为 CI 依赖。
- 不重写 V1 TaskOrchestrationApplicationService 为全新的 Agent runtime。
- 不新增复杂 Agent Trace 平台；Phase 5 只保留必要审计摘要。
- 不做 Memory Feedback Loop；失败经验进入长期记忆留到 Phase 7。
- 不做 Agent Evaluation Harness；评估体系留到 Phase 8。

## Further Notes

Phase 5 是 ProbeFlow 从“有 Planner、有安全门”走向“能恢复”的关键阶段。

前几个阶段的能力关系是：

- Phase 1：LLM 调用可控、可审计。
- Phase 2：工具能力被契约化。
- Phase 3：Planner 能产出结构化建议。
- Phase 4：建议必须经过策略安全门。
- Phase 5：安全建议可以在异常场景下受控改变计划。

面试中这一阶段可以这样讲：

```text
我没有让 LLM 自由循环，也没有让它直接执行工具。

我把异常恢复建模成显式 ReplanningTrigger。
每次异常只触发一次受控重规划：
StepOutcome 进入 PlannerInput，
Planner 产出 PlanDecision，
PolicyValidator 判断是否允许，
ReplanningApplicationService 只应用有限的计划变更。

成功历史不改写，pending 下游可以截断，等待人类有明确状态，
所有动作都有 blockers 和 audit summary。
```

这让 ProbeFlow 的 Agent 不只是“会调用 LLM”，而是能展示工程化 Agent 最重要的能力之一：

```text
失败后可解释、可恢复，但仍然可控。
```
