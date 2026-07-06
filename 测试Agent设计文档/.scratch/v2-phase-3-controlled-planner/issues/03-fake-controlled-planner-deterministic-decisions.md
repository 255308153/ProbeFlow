状态：ready-for-agent

# Issue 03：Fake Controlled Planner deterministic decisions

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

实现 FakeControlledPlanner，用于在 CI 和本地测试中稳定验证 Controlled Planner 行为。Fake planner 应支持固定决策、按 scenario 返回决策，并能模拟 planner failure 或 malformed output 场景。

这个 issue 的目标是先解决“Planner 决策如何确定性测试”的问题，避免真实 LLM 成为开发和 CI 的前置条件。

## Acceptance criteria

- [ ] 定义 ControlledPlanner 或等价 planner 接口。
- [ ] 实现 FakeControlledPlanner。
- [ ] Fake planner 可以返回确定性 CONTINUE 决策。
- [ ] Fake planner 可以返回确定性 INSERT_STEP 决策。
- [ ] Fake planner 可以返回确定性 REPLAN 决策。
- [ ] Fake planner 可以返回确定性 WAIT_FOR_HUMAN 决策，并包含 required human input。
- [ ] Fake planner 可以返回确定性 STOP 决策。
- [ ] Fake planner 可以按 scenario 或输入 metadata 选择不同决策。
- [ ] Fake planner 可以模拟 planner failure 或 malformed output。
- [ ] 测试不需要真实 LLM API key 或真实网络。

## Blocked by

- Issue 01：Planner decision domain model
- Issue 02：PlannerInput task and context snapshot
