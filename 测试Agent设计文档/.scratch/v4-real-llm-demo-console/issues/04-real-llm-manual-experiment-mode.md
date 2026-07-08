Status: ready-for-agent

# V4 Issue 04：真实 LLM 手动实验模式

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

在 V4 demo run 中加入真实 LLM 手动实验模式。真实 LLM 必须通过现有 `LlmProvider` seam 或等价 provider seam 接入，不允许 Controller、Demo Console 或 Harness 直接调用第三方模型。

真实 LLM 模式只能在显式配置和显式 run profile 下启用。默认测试、默认 CI、默认 demo run 仍使用 fake provider。缺少 key、模型、endpoint、超时、成本限制等配置时，系统要返回清晰的 rejected / failed demo result，而不是空报告或静默失败。

## Acceptance criteria

- [ ] 存在真实 LLM provider adapter 或等价接入点，并实现现有 provider contract。
- [ ] Demo run 可以通过显式 provider mode / run profile 选择 real LLM 模式。
- [ ] 未配置真实 LLM 时，demo result 返回清晰错误原因，并标记为 rejected 或 failed。
- [ ] 真实 LLM 超时、外部错误、无效结构化输出、policy 拦截都能被分类并进入 demo result。
- [ ] 真实 LLM 调用摘要包含用途、成功/失败、模型标识、token usage 或可用的等价统计。
- [ ] 真实 LLM 请求和响应经过既有审计、脱敏和策略边界，不泄漏 key 或敏感 header。
- [ ] 默认 CI 和默认 `mvn test` 不需要真实 LLM key，也不会访问真实外部模型。
- [ ] provider contract 测试使用 fake HTTP server、fake client 或等价方式验证真实 adapter 行为，不调用真实外部模型。

## Blocked by

- `01-demo-run-result-contract-and-fake-baseline.md`

