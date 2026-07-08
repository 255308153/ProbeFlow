Status: ready-for-agent

# V4 Issue 06：Demo 输出脱敏、记忆写入开关与安全边界

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

强化 V4 demo run 的安全边界，保证演示输出可展示、可审计、不会意外泄漏或污染长期记忆。Demo result、JSON artifact、Markdown artifact 和 Demo Console 都必须隐藏 secret、token、Authorization header、API key、真实模型 key 等敏感信息。

同时，真实 LLM 模式和 comparison 模式的记忆写入必须可控。默认情况下，comparison 不应写入长期记忆；real LLM 的 memory write 必须由 run profile 或明确开关决定，并记录来源证据、provider mode 和脱敏结果。

## Acceptance criteria

- [ ] Demo result 中的 secret、token、Authorization header、API key、模型 key 等敏感内容被统一脱敏。
- [ ] JSON artifact、Markdown artifact 和 Demo Console 展示内容都复用同一套脱敏结果或等价安全边界。
- [ ] Demo result 明确返回 `usesExternalHttp=false` 或等价字段，证明默认 demo 不误打真实业务服务。
- [ ] comparison 模式默认不写入长期记忆。
- [ ] real LLM 模式的 memory write 必须由明确配置或 run profile 开启，默认关闭。
- [ ] 任何 memory feedback 写入都保留来源证据、脱敏结果和 provider mode。
- [ ] 被 policy 拦截的工具调用或 LLM 行为能展示清晰原因。
- [ ] 有测试覆盖敏感字段脱敏、comparison 不污染长期记忆、real memory write 开关行为。

## Blocked by

- `01-demo-run-result-contract-and-fake-baseline.md`
- `04-real-llm-manual-experiment-mode.md`

