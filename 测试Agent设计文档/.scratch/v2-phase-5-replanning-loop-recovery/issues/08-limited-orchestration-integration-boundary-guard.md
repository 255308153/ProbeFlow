状态：ready-for-agent

# Issue 08：Limited orchestration integration and Phase 5 boundary guard

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

把 Replanning Loop 有限接入编排失败或暂停点，并补齐 V2 Phase 5 acceptance boundary guard。系统可以在明确失败/阻塞场景下调用重规划入口，但不能把 V1 编排重写成自由 Agent runtime。

这个 issue 的目标是保护 Phase 5 边界：交付受控异常恢复，不提前实现自由 AutoGPT loop、后台任务、分布式队列、多 Agent、完整 HITL、REST/frontend 或外部集成。

## Acceptance criteria

- [ ] TaskOrchestrationApplicationService 或等价编排层只在明确失败 / 暂停点有限调用 ReplanningApplicationService。
- [ ] V1 确定性模板计划仍然是默认执行路径。
- [ ] PlanStepRunner 仍然是既有步骤执行入口，没有被新 ToolRouter 替代。
- [ ] ManualReviewGate 行为保持兼容。
- [ ] Replanning Loop 不成为所有任务的必经路径。
- [ ] 边界测试证明没有实现自由 AutoGPT 式循环。
- [ ] 边界测试证明没有实现长期后台自治任务。
- [ ] 边界测试证明没有新增分布式队列或 worker。
- [ ] 边界测试证明没有实现多 Agent 协作。
- [ ] 边界测试证明没有实现完整 Human-in-the-loop workflow 或前端 review 页面。
- [ ] 边界测试证明没有新增 REST Controller 或 Web Console。
- [ ] 边界测试证明没有接入 Slack、Jira、GitHub issue、邮件、Webhook 或真实 CI。
- [ ] 边界测试证明没有引入 UI 自动化、浏览器自动化或端到端测试。
- [ ] 边界测试证明真实 LLM 仍不是 CI 必需依赖。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Replanning contract and explicit trigger entrypoint
- Issue 02：StepOutcome recovery snapshot into PlannerInput
- Issue 03：Policy-gated replanning decision handling
- Issue 04：Insert-step recovery path
- Issue 05：Truncate-and-replan downstream recovery path
- Issue 06：Wait-for-human and review-completed recovery path
- Issue 07：Replanning idempotency, attempt limits and terminal guards
