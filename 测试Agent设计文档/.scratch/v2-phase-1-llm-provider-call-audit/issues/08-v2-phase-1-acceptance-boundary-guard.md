状态：ready-for-agent

# Issue 08：V2 Phase 1 acceptance boundary guard

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

增加 V2 Phase 1 边界守护测试，确保本阶段只是 LLM Provider 与调用审计基础设施，不提前实现 Controlled Planner、Agent Loop、工具调用、业务对象写入、真实 LLM CI 依赖或产品化外围能力。

这些测试要保护 V2 的演进节奏：先安全接入 LLM，再做受控 Planner。

## Acceptance criteria

- [ ] 边界测试证明没有引入 Controlled Planner。
- [ ] 边界测试证明没有引入 Agent Loop。
- [ ] 边界测试证明 LLM 模块不能直接调用 ToolRouter 或 PlanStepRunner。
- [ ] 边界测试证明 LLM 模块不能直接调用 HTTP 执行器。
- [ ] 边界测试证明 LLM 模块不会修改 TestCase、ExecutionRecord、Observation、Memory、Report 的业务写路径。
- [ ] 边界测试证明 CI 不需要真实 LLM API key。
- [ ] 边界测试证明没有引入前端 UI。
- [ ] 边界测试证明没有引入 REST Controller。
- [ ] 边界测试证明没有引入队列或后台 worker。
- [ ] 边界测试证明没有引入外部通知、ticket、GitHub/Jira/Slack 集成。
- [ ] 边界测试证明没有引入 Python/pytest runner。

## Blocked by

- Issue 01：LLM domain model and provider abstraction
- Issue 02：Fake LLM Provider for deterministic CI
- Issue 03：Prompt template registry and rendering validation
- Issue 04：LLM call logging persistence and migration
- Issue 05：LlmApplicationService success and failure flow
- Issue 06：LLM execution policy and real-provider guardrails
- Issue 07：Audit safety and log sanitization
