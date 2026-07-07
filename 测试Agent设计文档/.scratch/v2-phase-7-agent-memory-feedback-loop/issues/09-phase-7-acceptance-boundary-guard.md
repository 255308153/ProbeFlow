状态：ready-for-agent

# Issue 09：Phase 7 acceptance boundary guard

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

补齐 V2 Phase 7 的验收边界测试，证明本阶段交付的是后端 Agent Core 的记忆反馈闭环，而不是提前实现产品 UI、企业知识治理后台、异步自治学习系统或黑盒自学习 LLM。

这个 issue 的目标是保护 Phase 7 边界：Agent 可以从经验中学习，但必须经过候选、审计、治理、召回和反馈评分，不允许绕过这些边界。

## Acceptance criteria

- [ ] 边界测试证明 Phase 7 没有新增 REST Controller。
- [ ] 边界测试证明 Phase 7 没有新增 Web Console 或前端页面。
- [ ] 边界测试证明 Phase 7 没有实现复杂权限系统或多人记忆审核后台。
- [ ] 边界测试证明 Phase 7 没有接入 Slack、邮件、Webhook、Jira、GitHub issue 或外部通知系统。
- [ ] 边界测试证明 Phase 7 没有新增异步队列、分布式 worker 或后台自治学习任务。
- [ ] 边界测试证明真实 LLM 不是 CI 必需依赖。
- [ ] 边界测试证明 LLM 不能直接写 LongTermMemory。
- [ ] 边界测试证明未校验的人类输入不能直接进入长期记忆。
- [ ] 边界测试证明原始 Observation、ExecutionRecord、HumanDecisionRecord、PolicyValidationResult 不会被自动删除。
- [ ] 边界测试证明 Phase 7 没有实现完整 Agent Evaluation Harness。
- [ ] 边界测试证明现有 MemoryRefinery、UnifiedContextBuilder、FailureAnalysis 和 HumanInTheLoop 工作流没有被重写。
- [ ] Phase 7 关键应用层路径有测试覆盖：candidate intake、human feedback refine、execution failure intake、policy learning note、merge、usage record、usefulness feedback、next-task recall。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Agent memory feedback contract and candidate audit foundation
- Issue 02：Human feedback candidates into MemoryRefinery
- Issue 03：Execution and StepOutcome candidate intake
- Issue 04：Policy and planner learning notes
- Issue 05：Memory dedup, merge and confidence reinforcement
- Issue 06：Memory usage record from context recall
- Issue 07：Usefulness feedback scoring loop
- Issue 08：Next-task recall learning loop acceptance path
