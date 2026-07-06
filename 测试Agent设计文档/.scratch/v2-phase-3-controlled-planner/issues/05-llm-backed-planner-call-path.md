状态：ready-for-agent

# Issue 05：LLM-backed planner call path

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

实现 LLM-backed planner 调用路径。Controlled Planner 应通过已有 LlmApplicationService 和 PromptTemplateRegistry 调用模型，使用 planner purpose/template，记录 llm call id，并保持 fake provider 可用于 CI。

这个 issue 不要求真实 provider 成为依赖，也不允许 planner 绕过 LlmApplicationService 直接调用模型 SDK。

## Acceptance criteria

- [ ] 实现 LLM-backed planner 或等价适配器。
- [ ] LLM-backed planner 通过 LlmApplicationService 发起调用。
- [ ] Planner prompt 通过 PromptTemplateRegistry 管理，包含 planner purpose 和版本。
- [ ] PlannerInput 可以被转换为紧凑 prompt 变量。
- [ ] LLM 调用结果中的 llm call id 可以关联到 PlanDecision。
- [ ] PlanDecision 能标记 fake provider 或真实 provider 来源。
- [ ] LLM-backed planner 可以使用 fake provider 在测试中返回确定性响应。
- [ ] 测试不需要真实 API key。
- [ ] 测试不需要真实网络。
- [ ] LLM-backed planner 不直接调用 LlmProvider 或第三方模型 SDK。

## Blocked by

- Issue 01：Planner decision domain model
- Issue 02：PlannerInput task and context snapshot
- Issue 04：ControlledPlannerService application entrypoint
