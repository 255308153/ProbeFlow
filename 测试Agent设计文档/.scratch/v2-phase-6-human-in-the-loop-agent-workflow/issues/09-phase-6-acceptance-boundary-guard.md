状态：ready-for-agent

# Issue 09：Phase 6 acceptance boundary guard

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

补齐 V2 Phase 6 的验收边界测试，证明本阶段交付的是后端 Agent Core 的 Human-in-the-loop 工作流，而不是提前实现产品 UI、企业审批系统、外部通知或自由 AutoGPT runtime。

这个 issue 的目标是保护架构边界：Phase 6 可以让 Agent 停下来问人、记录人类决策、带着反馈恢复，但不能越界成复杂协作产品。

## Acceptance criteria

- [ ] 边界测试证明 Phase 6 没有新增 REST Controller。
- [ ] 边界测试证明 Phase 6 没有新增 Web Console 或前端页面。
- [ ] 边界测试证明 Phase 6 没有实现复杂权限系统。
- [ ] 边界测试证明 Phase 6 没有实现多用户审批流。
- [ ] 边界测试证明 Phase 6 没有接入 Slack、邮件、Webhook、GitHub issue、Jira 或外部工单系统。
- [ ] 边界测试证明 Phase 6 没有新增后台通知系统或分布式 worker。
- [ ] 边界测试证明 Phase 6 没有引入真实 LLM 作为 CI 依赖。
- [ ] 边界测试证明 Phase 6 没有让 LLM 直接消费未校验的人类输入并修改数据库。
- [ ] 边界测试证明 Human-in-the-loop 与 Replanning 的耦合停留在应用层。
- [ ] 边界测试证明 V1 manual review 和 TaskOrchestration 默认流程没有被重写。
- [ ] Phase 6 关键应用层路径有测试覆盖：request 创建、decision 提交、draft review、blocker resolution、high-risk approval、planner clarification、memory candidate handoff。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
- Issue 02：WAITING_FOR_HUMAN request creation from Replanning
- Issue 03：Decision submission validation and audit sanitization
- Issue 04：Draft review workflow and ManualReviewGate compatibility
- Issue 05：Blocker resolution and missing input workflow
- Issue 06：High-risk approval and rejection workflow
- Issue 07：Planner clarification feedback workflow
- Issue 08：Human feedback memory candidate handoff
