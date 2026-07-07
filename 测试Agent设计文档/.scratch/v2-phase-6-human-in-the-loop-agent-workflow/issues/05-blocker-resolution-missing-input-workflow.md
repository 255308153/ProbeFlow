状态：ready-for-agent

# Issue 05：Blocker resolution and missing input workflow

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

实现 blocker resolution / missing input 的人工补充链路。当 Agent 缺少 baseUrl、token、环境、参数或任务前置条件时，系统创建结构化 human request；人类补充信息后，系统把输入写入可被 Replanning 消费的上下文。

完成后，Agent 不会在信息不足时强行继续，而是能明确提出缺口并在用户补齐后恢复。

## Acceptance criteria

- [ ] readiness missing、context missing 或等价 blocker 可以创建 blocker resolution / missing input request。
- [ ] request waiting reason 清楚表达缺少什么信息。
- [ ] required input schema 能描述需要的 baseUrl、token、environment、parameter、credential reference 或说明字段。
- [ ] provide input / resolve blocker decision 可以提交结构化补充信息。
- [ ] 合法补充信息写入 task metadata、PlannerInput context 或等价可恢复上下文。
- [ ] 未解决 blocker 时 task 保持等待，不会继续执行危险步骤。
- [ ] 人类补充信息中的敏感字段只以脱敏摘要进入审计。
- [ ] 同一 blocker 重试不会重复创建 pending request。
- [ ] completed / cancelled task 不会消费 blocker resolution decision。
- [ ] 本 issue 不实现真实密钥管理，不接入外部配置中心。
- [ ] 测试覆盖 blocker request 创建、schema、合法补充、未解决保持等待、脱敏、幂等和终态任务拒绝。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
- Issue 03：Decision submission validation and audit sanitization
