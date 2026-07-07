状态：ready-for-agent

# Issue 04：Provider, external HTTP safety, diagnostics and redaction

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

补齐 harness 的安全边界、诊断和统一脱敏。系统必须默认使用 deterministic fake provider 和 fake HTTP gateway；manual real LLM 必须显式开启并受 policy 约束；未知 fixture、无效 fixture、不允许的 provider mode、外部 HTTP 禁用和报告写入失败都要返回结构化诊断。

这个 issue 的目标是让 V3-1 能放心用于同事协作、CI 和面试演示：默认不产生外部调用，不泄露敏感信息，失败时也能看懂原因。

## Acceptance criteria

- [ ] Harness 默认 provider mode 是 deterministic fake 或等价 fake 模式。
- [ ] Harness 默认禁止真实外部 HTTP。
- [ ] Manual real LLM 模式必须通过显式 request/config 开启。
- [ ] 未显式允许真实 LLM 时，manual real LLM run 返回 provider blocked 或等价结构化诊断。
- [ ] 不允许的 provider mode 返回清晰诊断，不进入执行路径。
- [ ] 未知 fixture 返回 fixture not found 诊断。
- [ ] 缺少必填字段或格式错误的 fixture 返回 fixture invalid 诊断。
- [ ] 报告写入失败返回 report write failed 或等价诊断。
- [ ] 运行失败时仍尽可能返回 run result 和 diagnostics；如果能写 artifact，应写出诊断报告。
- [ ] JSON report、Markdown report、diagnostics 和日志摘要中统一脱敏 authorization、cookie、password、secret、token、apiKey、credential 等敏感键。
- [ ] 脱敏覆盖 nested map、headers、body、env、auth、diagnostic metadata 和 artifact metadata。
- [ ] Report 中明确展示 usesRealLlm、usesExternalHttp、providerMode、fixtureId 和 run profile。
- [ ] 测试覆盖 provider blocked、fixture not found、fixture invalid、report write failure、no external HTTP、usesRealLlm 标记和敏感信息脱敏。

## Blocked by

- Issue 01：Harness contract, fixture registry and smoke run foundation
