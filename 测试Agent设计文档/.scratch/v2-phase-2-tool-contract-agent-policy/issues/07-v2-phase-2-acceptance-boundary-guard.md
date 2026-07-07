状态：ready-for-agent

# Issue 07：V2 Phase 2 acceptance boundary guard

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

补齐 V2 Phase 2 acceptance boundary guard，证明本阶段只交付 Tool Contract 与 Agent Policy 基础能力，没有提前引入 Controlled Planner、ToolRouter 真实执行、Replanning Loop、Human-in-the-loop workflow、Agent Evaluation、REST/frontend/queue/worker 或外部集成。

这个 issue 的目标是防止 Agent Core 路线膨胀，也让后续面试讲解时能证明每个阶段边界清晰。

## Acceptance criteria

- [ ] 边界测试证明本阶段没有引入 Controlled Planner。
- [ ] 边界测试证明本阶段没有引入 LLM planner 结构化输出解析。
- [ ] 边界测试证明本阶段没有引入 ToolRouter 真实执行。
- [ ] 边界测试证明本阶段没有引入 Replanning Loop。
- [ ] 边界测试证明本阶段没有引入 Human-in-the-loop workflow。
- [ ] 边界测试证明本阶段没有引入 Agent Evaluation。
- [ ] 边界测试证明本阶段没有新增 REST Controller 或 Web Console。
- [ ] 边界测试证明本阶段没有新增 queue、worker 或外部集成。
- [ ] 边界测试证明没有登记 GitHub、Jira、Slack、webhook、MCP、plugin marketplace 或真实 CI 操作工具。
- [ ] 边界测试证明 LLM/Planner 不会获得 Java service、repository、database、HTTP client 或 Spring bean 实例。
- [ ] 边界测试证明真实 LLM 仍不是 CI 必需依赖。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Tool Contract domain model and naming protocol
- Issue 02：Internal ToolContractRegistry catalog
- Issue 03：AgentPolicy whitelist and policy decisions
- Issue 04：Tool input schema and precondition validation
- Issue 05：High-risk tools and V1 boundary safety policy
- Issue 06：Planner-safe tool catalog view
