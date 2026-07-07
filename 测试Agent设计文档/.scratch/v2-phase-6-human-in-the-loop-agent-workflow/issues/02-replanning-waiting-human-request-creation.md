状态：ready-for-agent

# Issue 02：WAITING_FOR_HUMAN request creation from Replanning

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

让 Phase 5 的 Replanning Loop 在进入 `WAITING_FOR_HUMAN` / `WAITING_FOR_REVIEW` 时创建正式的 HumanReviewRequest，而不是只依赖 Task metadata 表达等待状态。

完成后，Agent 暂停时会留下明确的等待请求，编排层可以通过 active request 判断任务为什么停下、需要谁补充什么输入。

## Acceptance criteria

- [ ] Replanning Loop 的 `WAITING_FOR_HUMAN` 结果可以创建 HumanReviewRequest。
- [ ] review pending 场景可以创建 draft review 类型的 HumanReviewRequest。
- [ ] context missing 或 readiness missing 场景可以创建 missing input / blocker resolution 类型的 HumanReviewRequest。
- [ ] request waiting reason 能清楚说明 Agent 为什么停下来。
- [ ] request required input schema 能描述需要人类补充的字段。
- [ ] request metadata 记录 source trigger 和关联的 plan step / blocker 摘要。
- [ ] 同一个 task / trigger / source step / blocker 重试时不会创建重复 pending request。
- [ ] active request lookup 可以让编排层判断任务是否仍在等待人类。
- [ ] task 状态继续表达 WAITING_FOR_REVIEW 或等价等待状态。
- [ ] 本 issue 不消费人类 decision，不触发恢复，不实现 UI。
- [ ] 测试覆盖 request 创建、幂等创建、等待原因、schema、metadata 和 task 状态。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
