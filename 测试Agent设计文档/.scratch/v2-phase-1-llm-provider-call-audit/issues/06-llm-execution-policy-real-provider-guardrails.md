状态：ready-for-agent

# Issue 06：LLM execution policy and real-provider guardrails

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

增加 LLM 执行策略与真实 provider 护栏。系统必须能表达“只允许 fake provider”“测试环境禁止真实网络”“真实 provider 必须显式配置启用”等策略。policy 拒绝时不能访问 provider，并且需要返回和记录清晰的 POLICY_BLOCKED 结果。

这个 issue 的目标是防止 LLM 基础设施在测试或 CI 中误触发真实模型调用。

## Acceptance criteria

- [ ] 定义 LLM execution policy 或等价策略对象。
- [ ] 定义 LLM execution options，包含 provider、model、temperature、max tokens、timeout、retry 等配置。
- [ ] 支持只允许 fake provider 的策略。
- [ ] test profile 默认禁止真实 provider。
- [ ] 真实 provider 必须显式配置启用。
- [ ] policy 拒绝时不访问 provider。
- [ ] policy 拒绝时返回 POLICY_BLOCKED。
- [ ] policy 拒绝时写入或产生可审计结果。
- [ ] 测试证明 CI 不需要真实 LLM API key。
- [ ] 测试证明禁用真实 provider 时不会访问真实网络。

## Blocked by

- Issue 05：LlmApplicationService success and failure flow
