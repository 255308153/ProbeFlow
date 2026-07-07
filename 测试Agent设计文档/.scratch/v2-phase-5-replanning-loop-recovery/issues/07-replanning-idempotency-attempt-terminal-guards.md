状态：ready-for-agent

# Issue 07：Replanning idempotency, attempt limits and terminal guards

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

为 Replanning Loop 补齐循环保护、幂等保护和终态保护。系统必须避免同一个触发器反复插入恢复步骤，也不能对 completed / cancelled task 做隐式重规划。

这个 issue 的核心是证明 Phase 5 是“有限重规划”，不是无限自我驱动循环。

## Acceptance criteria

- [ ] 同一个 task、trigger、source step、decision id 或等价 trigger key 重复调用时不会重复插入 PlanStep。
- [ ] 重复触发返回 NOOP、NOT_TRIGGERABLE 或等价幂等结果。
- [ ] Task metadata 或等价审计状态记录 replan attempt count。
- [ ] 超过单 task 最大 attempt 后返回 NOT_TRIGGERABLE 或 FAILED。
- [ ] 超过单 trigger 最大 attempt 后返回 NOT_TRIGGERABLE 或 FAILED。
- [ ] retry count 被尊重，不允许无限重试同一个失败步骤。
- [ ] completed task 不可重规划。
- [ ] cancelled task 不可重规划。
- [ ] failed task 只能通过显式 recovery trigger 恢复，不能被普通调用偷偷复活。
- [ ] 所有 plan mutation 在事务边界内完成，失败时不会出现 Task / PlanStep 半更新。
- [ ] ReplanningResult 或 audit summary 包含 attempt count。
- [ ] 测试覆盖重复触发、attempt 上限、completed/cancelled 保护、failed task 显式恢复和事务回滚。

## Blocked by

- Issue 04：Insert-step recovery path
- Issue 05：Truncate-and-replan downstream recovery path
- Issue 06：Wait-for-human and review-completed recovery path
