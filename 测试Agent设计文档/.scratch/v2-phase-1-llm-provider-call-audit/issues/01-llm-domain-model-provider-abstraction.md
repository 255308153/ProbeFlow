状态：ready-for-agent

# Issue 01：LLM domain model and provider abstraction

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

建立 V2 Phase 1 的 LLM 基础领域模型和 provider 抽象。系统需要有统一的 LLM 请求、响应、调用结果、状态枚举、错误分类和 token usage 表达方式，让后续 fake provider、真实 provider、prompt 模板、调用日志和应用服务都基于同一套协议工作。

这个 issue 只建立协议和核心类型，不接真实模型，不写调用日志，不进入 Agent Loop。

## Acceptance criteria

- [ ] 定义 LLM provider 抽象，屏蔽不同模型供应商差异。
- [ ] 定义结构化 LLM 请求对象。
- [ ] 定义结构化 LLM 响应对象。
- [ ] 定义结构化 LLM 调用结果对象。
- [ ] 定义稳定调用状态枚举，例如 SUCCESS、FAILED、BLOCKED、SKIPPED。
- [ ] 定义稳定错误分类，例如 TIMEOUT、RATE_LIMITED、PROVIDER_ERROR、NETWORK_ERROR、TEMPLATE_RENDER_ERROR、POLICY_BLOCKED、OUTPUT_PARSE_ERROR。
- [ ] 定义 token usage、provider trace id、model、provider 等归一化字段。
- [ ] LLM 基础类型不依赖 orchestration 内部实现。
- [ ] 测试覆盖 provider 协议和结果对象的基本行为。

## Blocked by

None - can start immediately.
