状态：ready-for-agent

# Issue 07：Planner-safe boundary and non-execution constraints

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

补齐 Planner-safe 边界与非执行约束验证。Controlled Planner 必须只能看到 planner-safe tool view，不能接触 Java service、repository、database、HTTP client 或 Spring bean；PlanDecision 也不得导致 Task、PlanStep、Memory、Observation、ExecutionRecord 或 Report 发生写入。

这个 issue 的目标是把“Planner 只建议，不执行”变成可验证的工程边界。

## Acceptance criteria

- [ ] 测试证明 Planner 输入只包含 PlannerSafeToolView 或等价 safe metadata。
- [ ] 测试证明 Planner 输入不暴露 ToolContract 内部实现之外的执行入口。
- [ ] 测试证明 Planner 输入不暴露 Java service 类名或实例。
- [ ] 测试证明 Planner 输入不暴露 repository、database、HTTP client 或 Spring bean。
- [ ] 测试证明 ControlledPlannerService 不修改 Task。
- [ ] 测试证明 ControlledPlannerService 不插入、删除或重排 PlanStep。
- [ ] 测试证明 ControlledPlannerService 不调用 ToolRouter、PlanStepRunner 或真实工具执行。
- [ ] 测试证明 ControlledPlannerService 不写 Memory。
- [ ] 测试证明 ControlledPlannerService 不写 Observation、ExecutionRecord 或 Report。
- [ ] 测试证明真实 LLM 仍不是 CI 必需依赖。

## Blocked by

- Issue 02：PlannerInput task and context snapshot
- Issue 04：ControlledPlannerService application entrypoint
- Issue 05：LLM-backed planner call path
- Issue 06：PlanDecision parser and safe fallback
