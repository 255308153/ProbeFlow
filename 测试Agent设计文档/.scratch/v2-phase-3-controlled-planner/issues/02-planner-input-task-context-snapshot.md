状态：ready-for-agent

# Issue 02：PlannerInput task and context snapshot

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

构建面向 Planner 的 PlannerInput 与任务快照。PlannerInput 应聚合 task state、当前 task phase、workflow mode、last step outcome、context summary、planner-safe tool catalog 和约束信息，让 Planner 可以基于受控输入做决策，而不是直接读取数据库实体或调用内部服务。

这个 issue 的重点是建立“Planner 看到什么”，并确保输入紧凑、可审计、不会暴露实现细节。

## Acceptance criteria

- [ ] 定义 PlannerInput，包含 task state、current phase、workflow mode、last step outcome、context summary、available tools 和 constraints。
- [ ] 定义面向 Planner 的 TaskState snapshot，不直接暴露 JPA entity。
- [ ] 定义 ContextBundleSummary 或等价摘要对象，只携带规划需要的摘要、引用和数量。
- [ ] 定义 LastStepOutcome snapshot，可由现有 StepOutcome 或等价结果映射而来。
- [ ] PlannerInput 使用 Planner-safe 工具目录视图作为工具输入。
- [ ] PlannerInput 包含 AgentPolicy 相关的工具状态或约束信息。
- [ ] PlannerInput 不包含 Java service、repository、database、HTTP client 或 Spring bean。
- [ ] PlannerInput 不携带无限长原文，优先使用摘要、引用和计数。
- [ ] 测试覆盖 task state、phase、workflow mode、last outcome、context summary、tools 和 constraints 的构建行为。

## Blocked by

- Issue 01：Planner decision domain model
