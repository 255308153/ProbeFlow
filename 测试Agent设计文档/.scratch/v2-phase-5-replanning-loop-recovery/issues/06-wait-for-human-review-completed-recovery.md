状态：ready-for-agent

# Issue 06：Wait-for-human and review-completed recovery path

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

支持等待人类和 review 完成后的恢复路径。系统在 readiness 不足、缺少输入、高风险确认等场景下可以安全暂停，并在 review completed trigger 到来后恢复原模板计划或继续后续步骤。

这个 issue 不实现完整 Human-in-the-loop UI，只建立 Phase 6 可以复用的暂停、输入请求和恢复契约。

## Acceptance criteria

- [ ] WAIT_FOR_HUMAN decision 经 policy 处理后可以让 ReplanningResult 返回 WAITING_FOR_HUMAN。
- [ ] 等待状态记录 required human input 的 reason、question、input schema 或等价信息。
- [ ] readiness missing trigger 可以进入等待人类路径。
- [ ] high-risk confirmation 场景可以进入等待人类路径。
- [ ] 等待人类路径不执行工具。
- [ ] 等待人类路径不插入非必要 PlanStep。
- [ ] 等待人类路径不修改成功历史。
- [ ] review completed trigger 可以恢复暂停任务。
- [ ] review completed trigger 可以返回 keep template / resume 结果。
- [ ] review completed trigger 与现有 ManualReviewGate 行为兼容，不破坏半自动 review。
- [ ] ReplanningResult 包含 blockers 或恢复摘要，说明下一步等待什么或恢复了什么。
- [ ] 测试覆盖 readiness missing 等待、人类输入信息记录、高风险确认等待、review completed 恢复。

## Blocked by

- Issue 03：Policy-gated replanning decision handling
