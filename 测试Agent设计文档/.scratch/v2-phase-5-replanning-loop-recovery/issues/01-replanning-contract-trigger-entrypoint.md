状态：ready-for-agent

# Issue 01：Replanning contract and explicit trigger entrypoint

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

建立 Replanning Loop 的最小契约和显式触发入口。系统需要有稳定的 ReplanningTrigger、ReplanningRequest、ReplanningResult 和 ReplanningApplicationService，让异常恢复流程可以被调用、审计和测试。

这个 issue 只交付最小入口和结果模型，先支持可审计的 NOOP / NOT_TRIGGERABLE 结果，不调用 Planner，不校验 Policy，不修改计划。

## Acceptance criteria

- [ ] 定义 ReplanningTrigger 或等价枚举，至少覆盖 plan step failed、execution readiness missing、failure analysis high risk、review completed、context missing、human input required。
- [ ] ReplanningTrigger 是白名单模型，不接受任意字符串触发恢复。
- [ ] 定义 ReplanningRequest 或等价输入对象，包含 task id、trigger、可选 step id、可选 StepOutcome 摘要、可选人类输入 / review 完成信号。
- [ ] 定义 ReplanningResult 或等价输出对象，包含 status、trigger、blockers、decision summary、policy summary、plan mutation summary、inserted step ids、skipped step ids。
- [ ] ReplanningResult status 至少能表达 APPLIED、NOOP、WAITING_FOR_HUMAN、REJECTED_BY_POLICY、NOT_TRIGGERABLE、FAILED。
- [ ] 新增 ReplanningApplicationService 或等价应用层入口。
- [ ] 空 request、空 task id、空 trigger 有明确失败行为。
- [ ] completed task 返回 NOT_TRIGGERABLE 或等价结果。
- [ ] cancelled task 返回 NOT_TRIGGERABLE 或等价结果。
- [ ] 最小合法 request 可以返回 NOOP，并包含可审计摘要。
- [ ] 本 issue 不调用 LLM、不调用 Planner、不调用 PolicyValidator、不修改 Task 或 PlanStep。
- [ ] 测试覆盖结果契约、显式 trigger、NOOP、NOT_TRIGGERABLE 和非法输入。

## Blocked by

None - can start immediately.
