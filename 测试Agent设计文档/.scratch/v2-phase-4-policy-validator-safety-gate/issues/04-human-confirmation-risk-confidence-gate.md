状态：ready-for-agent

# Issue 04：Human confirmation, risk level and confidence gate

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

扩展 Policy Validator 的人工确认安全门。系统需要根据工具 contract、workflow mode、PlannerDecision risk level 和 confidence 决定建议是否可以自动继续，还是必须等待人工确认或被阻塞。

这个 issue 是后续 Human-in-the-loop workflow 的入口契约，但不实现完整人工交互流程。

## Acceptance criteria

- [ ] contract 标记 human confirmation required 的工具返回 REQUIRES_HUMAN_CONFIRMATION。
- [ ] execution mode 为 human confirmation required 的工具返回 REQUIRES_HUMAN_CONFIRMATION。
- [ ] review required workflow 下的非只读工具返回 REQUIRES_HUMAN_CONFIRMATION。
- [ ] 高风险或 critical risk 的 PlannerDecision 不得静默返回 ALLOWED。
- [ ] 低置信度 PlannerDecision 不得静默返回 ALLOWED。
- [ ] WAIT_FOR_HUMAN 的 required human input 被带入 validation result，方便后续交互层展示。
- [ ] 人工确认结果包含稳定 reason code 和用户可读 message。
- [ ] 人工确认不执行工具、不修改 Task、不修改 PlanStep。
- [ ] 测试覆盖 contract 要求确认、review required workflow、高风险 decision、低置信度 decision 和 WAIT_FOR_HUMAN input 透传。

## Blocked by

- Issue 02：PlannerDecision status and action safety validation
- Issue 03：Proposed tool visibility and AgentPolicy validation
