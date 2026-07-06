状态：ready-for-agent

# Issue 01：Planner decision domain model

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

建立 Controlled Planner 的核心决策领域模型。系统需要有稳定的 Planner action、PlanDecision、RequiredHumanInput、ProposedPlanStep、reasoning、confidence、risk、source llm call id 和 fake provider 标记，让后续 fake planner、LLM planner、结构化解析和边界测试都基于同一套协议工作。

这个 issue 只建立协议和核心类型，不调用 LLM，不构建 PlannerInput，不执行工具，也不修改 Task 或 PlanStep。

## Acceptance criteria

- [ ] 定义 Planner action，至少包含 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP。
- [ ] 定义 PlanDecision，作为 Controlled Planner 的唯一输出对象。
- [ ] PlanDecision 包含 action、reasoning、confidence、risk level、proposed tool、proposed PlanStep、required human input、blockers、source llm call id、fake provider flag 等字段。
- [ ] 定义 RequiredHumanInput，包含 reason、question、input schema 和 blocking flag。
- [ ] 定义 ProposedPlanStep 或等价建议对象，但不落库。
- [ ] confidence 有稳定范围或归一化规则。
- [ ] risk level 与现有 Tool Contract 风险等级兼容。
- [ ] PlanDecision 能表达安全失败或 blocked 结果。
- [ ] PlanDecision 不依赖 JPA entity、Java service、repository、HTTP client 或 Spring bean。
- [ ] 测试覆盖五种 action、required human input、risk/confidence、blocked/failed decision 和基本序列化/摘要行为。

## Blocked by

None - can start immediately.
