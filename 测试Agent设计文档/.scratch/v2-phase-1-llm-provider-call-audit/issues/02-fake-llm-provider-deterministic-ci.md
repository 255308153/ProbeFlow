状态：ready-for-agent

# Issue 02：Fake LLM Provider for deterministic CI

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

实现确定性的 Fake LLM Provider，让 LLM 基础设施可以在 CI 和本地测试中稳定运行，不依赖真实网络、API key 或模型供应商。Fake provider 应支持固定响应、按请求或模板返回响应、模拟 token usage，并能模拟常见错误。

这个 issue 的目标是先解决“非确定性 LLM 如何测试”的问题。

## Acceptance criteria

- [ ] 实现 Fake LLM Provider。
- [ ] Fake provider 可以返回确定性成功响应。
- [ ] Fake provider 可以模拟 provider error、timeout、rate limit 或 parse error 等错误。
- [ ] Fake provider 可以返回确定性 token usage。
- [ ] 调用结果能标识是否来自 fake provider。
- [ ] 测试环境可以只使用 fake provider。
- [ ] 测试不需要真实 API key。
- [ ] 测试不需要真实网络。
- [ ] 测试覆盖成功响应、错误模拟和 token usage。

## Blocked by

- Issue 01：LLM domain model and provider abstraction
