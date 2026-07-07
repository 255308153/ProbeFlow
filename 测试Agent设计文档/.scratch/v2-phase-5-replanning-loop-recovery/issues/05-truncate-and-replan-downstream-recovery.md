状态：ready-for-agent

# Issue 05：Truncate-and-replan downstream recovery path

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

支持受控截断下游计划并追加恢复动作。Planner 给出 safe REPLAN 或等价恢复建议后，系统可以把失败点之后仍未执行的 pending downstream steps 标记为 skipped，并追加恢复步骤或进入 safe stop。

这个 issue 的重点是保护计划历史：成功步骤不可改写，running 步骤不可变更，失败事实不可抹除。

## Acceptance criteria

- [ ] Policy allowed 的 REPLAN decision 可以触发 downstream recovery。
- [ ] 只允许截断 pending downstream steps。
- [ ] 被截断的 pending downstream steps 标记为 SKIPPED。
- [ ] 成功历史 PlanStep 不会被重写、删除或改成 pending。
- [ ] RUNNING PlanStep 不会被重规划直接修改。
- [ ] FAILED PlanStep 保持 FAILED，不能被抹除。
- [ ] 可以在截断后追加一个受控恢复步骤。
- [ ] 可以在高风险场景下返回 safe stop，不追加新步骤。
- [ ] ReplanningResult 包含 skipped step ids 和 inserted step ids。
- [ ] ReplanningResult 的 blockers / summary 能解释为什么截断下游计划。
- [ ] 支持 high-risk failure analysis trigger 的截断 / stop 场景。
- [ ] 测试覆盖 pending downstream skipped、成功历史保护、running step 保护、safe stop 和追加恢复步骤。

## Blocked by

- Issue 03：Policy-gated replanning decision handling
- Issue 04：Insert-step recovery path
