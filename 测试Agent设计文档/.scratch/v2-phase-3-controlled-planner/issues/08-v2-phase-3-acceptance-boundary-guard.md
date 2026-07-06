状态：ready-for-agent

# Issue 08：V2 Phase 3 acceptance boundary guard

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

补齐 V2 Phase 3 acceptance boundary guard，证明本阶段只交付 Controlled Planner 的结构化建议能力，没有提前实现完整 Policy Validator、ToolRouter 真实执行、Replanning Loop、Human-in-the-loop workflow、Agent Evaluation、REST/frontend/queue/worker 或外部集成。

这个 issue 的目标是保护 Agent Core 的阶段边界，让 Phase 3 在面试中能清楚表达为“受控决策建议层”。

## Acceptance criteria

- [ ] 边界测试证明本阶段没有实现完整 Policy Validator。
- [ ] 边界测试证明本阶段没有新增 PlannerDecision 持久化审计表。
- [ ] 边界测试证明本阶段没有引入 ToolRouter 真实执行。
- [ ] 边界测试证明本阶段没有修改 TaskOrchestrationApplicationService 默认执行流。
- [ ] 边界测试证明本阶段没有自动插入、删除或重排 PlanStep。
- [ ] 边界测试证明本阶段没有引入 Replanning Loop。
- [ ] 边界测试证明本阶段没有引入 Human-in-the-loop workflow。
- [ ] 边界测试证明本阶段没有引入 Agent Memory Feedback Loop。
- [ ] 边界测试证明本阶段没有引入 Agent Evaluation Harness。
- [ ] 边界测试证明本阶段没有新增 REST Controller、Web Console、queue、worker 或外部集成。
- [ ] 边界测试证明没有引入 MCP、插件市场、GitHub/Jira/Slack/webhook 或真实 CI 集成。
- [ ] 边界测试证明真实 LLM 仍不是 CI 必需依赖。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Planner decision domain model
- Issue 02：PlannerInput task and context snapshot
- Issue 03：Fake Controlled Planner deterministic decisions
- Issue 04：ControlledPlannerService application entrypoint
- Issue 05：LLM-backed planner call path
- Issue 06：PlanDecision parser and safe fallback
- Issue 07：Planner-safe boundary and non-execution constraints
