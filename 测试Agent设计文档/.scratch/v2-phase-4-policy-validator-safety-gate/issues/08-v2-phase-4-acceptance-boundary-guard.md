状态：ready-for-agent

# Issue 08：V2 Phase 4 acceptance boundary guard

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

补齐 V2 Phase 4 acceptance boundary guard，证明本阶段只交付 Policy Validator 与安全拦截能力，没有提前实现 ToolRouter 真实执行、完整 Agent Loop、Replanning Loop、Human-in-the-loop workflow、REST/frontend/queue/worker 或外部集成。

这个 issue 的目标是保护 Agent Core 的阶段边界，让 Phase 4 在面试中能清楚表达为“Planner 与执行层之间的确定性安全门”。

## Acceptance criteria

- [ ] 边界测试证明本阶段没有引入 ToolRouter 真实执行。
- [ ] 边界测试证明本阶段没有把 Policy Validator 接入完整 Agent Loop 执行闭环。
- [ ] 边界测试证明本阶段没有修改 TaskOrchestrationApplicationService 默认执行流。
- [ ] 边界测试证明本阶段没有自动插入、删除或重排持久化 PlanStep。
- [ ] 边界测试证明本阶段没有实现 Replanning Loop。
- [ ] 边界测试证明本阶段没有实现 Human-in-the-loop workflow。
- [ ] 边界测试证明本阶段没有实现用户、团队、权限或审批流。
- [ ] 边界测试证明本阶段没有新增 REST Controller、Web Console、queue、worker 或外部集成。
- [ ] 边界测试证明没有引入 Slack、邮件、Webhook、GitHub issue、Jira 或真实 CI 集成。
- [ ] 边界测试证明没有引入 UI 自动化、浏览器自动化或端到端测试能力。
- [ ] 边界测试证明真实 LLM 仍不是 CI 必需依赖。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Policy validation result contract and audit summary
- Issue 02：PlannerDecision status and action safety validation
- Issue 03：Proposed tool visibility and AgentPolicy validation
- Issue 04：Human confirmation, risk level and confidence gate
- Issue 05：V1 backend API testing boundary blockers
- Issue 06：Task phase order and precondition validation
- Issue 07：PolicyValidatorService end-to-end composition
