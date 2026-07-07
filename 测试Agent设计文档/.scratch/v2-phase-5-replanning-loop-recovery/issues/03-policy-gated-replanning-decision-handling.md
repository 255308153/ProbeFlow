状态：ready-for-agent

# Issue 03：Policy-gated replanning decision handling

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

把 ControlledPlanner 与 PolicyValidator 接入 ReplanningApplicationService，但不直接应用复杂计划变更。所有恢复建议必须先经过 PolicyValidator，只有安全结果才能进入后续 plan mutation。

这个 issue 要证明三条基础路径：policy blocked 不改计划，requires human confirmation 暂停等待，safe continue 保留模板计划。

## Acceptance criteria

- [ ] ReplanningApplicationService 能调用 ControlledPlanner 或 fake planner 获取 PlanDecision。
- [ ] 每个 PlanDecision 在应用前必须调用 PolicyValidatorService。
- [ ] PolicyValidationResult 为 BLOCKED 时，ReplanningResult 返回 REJECTED_BY_POLICY。
- [ ] Policy blocked 路径不修改 Task。
- [ ] Policy blocked 路径不修改任何 PlanStep。
- [ ] PolicyValidationResult 为 REQUIRES_HUMAN_CONFIRMATION 时，ReplanningResult 返回 WAITING_FOR_HUMAN。
- [ ] 等待人工确认路径可以在 Task metadata 或等价审计状态中记录 required human input。
- [ ] CONTINUE decision 且 policy allowed 时，ReplanningResult 返回 NOOP 或等价 keep template 结果。
- [ ] CONTINUE decision 不插入、不删除、不重排 PlanStep。
- [ ] ReplanningResult 包含 decision audit summary 和 policy validation summary。
- [ ] 测试覆盖 blocked、requires human confirmation、safe continue 三条端到端路径。

## Blocked by

- Issue 01：Replanning contract and explicit trigger entrypoint
- Issue 02：StepOutcome recovery snapshot into PlannerInput
