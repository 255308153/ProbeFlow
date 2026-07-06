状态：ready-for-agent

# Issue 04：ControlledPlannerService application entrypoint

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

实现 ControlledPlannerService 作为 V2 Phase 3 的最高层应用入口。调用方给定 PlannerInput 或可构建 PlannerInput 的请求后，服务应调用受控 planner 并返回结构化 PlanDecision。

这个 issue 必须保持 Planner 是“建议者”而不是“执行器”：不得修改 Task，不得插入 PlanStep，不得调用 ToolRouter，不得执行 HTTP，不得写 Memory 或 Report。

## Acceptance criteria

- [ ] 实现 ControlledPlannerService 或等价应用服务。
- [ ] 服务可以接收 PlannerInput 并返回 PlanDecision。
- [ ] 服务可以使用 FakeControlledPlanner 返回稳定决策。
- [ ] 服务支持 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP 五种 action 的外部行为。
- [ ] WAIT_FOR_HUMAN 决策必须包含可行动的人类输入说明。
- [ ] 低 confidence 或高 risk 决策能被明确表达，而不是被自动执行。
- [ ] 服务不修改 Task。
- [ ] 服务不插入、删除或重排 PlanStep。
- [ ] 服务不调用 ToolRouter 或 PlanStepRunner。
- [ ] 服务不执行任何工具。
- [ ] 服务不写 Memory、Observation、ExecutionRecord 或 Report。
- [ ] 测试通过 ControlledPlannerService 验证外部行为，不测试私有 helper。

## Blocked by

- Issue 01：Planner decision domain model
- Issue 02：PlannerInput task and context snapshot
- Issue 03：Fake Controlled Planner deterministic decisions
