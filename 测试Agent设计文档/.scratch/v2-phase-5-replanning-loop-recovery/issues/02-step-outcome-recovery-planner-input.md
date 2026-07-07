状态：ready-for-agent

# Issue 02：StepOutcome recovery snapshot into PlannerInput

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

让 ReplanningApplicationService 能把 Task、PlanStep、StepOutcome、blockers 和 result refs 转成恢复场景下的 PlannerInput。Planner 必须能看到最新失败或阻塞原因，而不是只基于静态任务信息做决策。

这个 issue 需要形成一条可验证路径：给定失败步骤和 StepOutcome，系统能构造包含 last step outcome、任务状态、当前阶段、available tools 和约束的 PlannerInput，但仍不应用任何计划变更。

## Acceptance criteria

- [ ] ReplanningRequest 中的 StepOutcome 信息能进入 PlannerInput 的 last step outcome 或等价快照。
- [ ] 失败步骤的 step id、step type、step status 能进入恢复上下文。
- [ ] blocker details 能进入 PlannerInput，供 Planner 判断恢复动作。
- [ ] result refs 能进入 PlannerInput，供 Planner 引用 API spec、case、execution、report 等结果。
- [ ] PlannerInput 保留 task state、current phase、workflow mode、context bundle summary。
- [ ] PlannerInput 仍包含当前 AgentPolicy 允许的 available tools。
- [ ] 恢复场景下的 PlannerInput 复用已有 Phase 3 构建模型，不新造一套平行上下文协议。
- [ ] 支持 plan step failed trigger 的输入快照。
- [ ] 支持 execution readiness missing trigger 的输入快照。
- [ ] 支持 context missing trigger 的输入快照。
- [ ] Fake planner 可以基于恢复快照产出确定性恢复建议。
- [ ] 测试覆盖失败 StepOutcome、blocker details、result refs、available tools 和 fake planner 场景。

## Blocked by

- Issue 01：Replanning contract and explicit trigger entrypoint
