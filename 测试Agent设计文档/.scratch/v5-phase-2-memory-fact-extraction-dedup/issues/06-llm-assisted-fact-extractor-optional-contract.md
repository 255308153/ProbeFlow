Status: ready-for-agent

# V5-2 Issue 06：LLM-assisted Fact Extractor 可选契约与默认隔离

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

为 Memory Fact Extraction 预留 LLM-assisted extractor 契约，但保证默认路径仍是 deterministic / fake-friendly，默认测试和 CI 不调用真实 LLM。

完成后，系统可以通过显式配置选择 LLM-assisted fact extraction seam。LLM 输出必须被解析为受控的 Memory Fact schema，并继续经过 sanitization、quality gate、dedup / merge 和 embedding 写入流程。非法输出、缺字段、幻觉式事实或敏感内容不能绕过门禁进入长期记忆。

这个 issue 不是要把真实 LLM 做成默认能力，而是让未来真实 LLM 实验有清晰合同，同时保护默认测试稳定。

## Acceptance criteria

- [ ] 存在可选 LLM-assisted fact extractor 契约或等价扩展点。
- [ ] 默认配置仍使用 deterministic extractor，不调用真实 LLM。
- [ ] 默认 `mvn test` 在无真实 LLM key、无外部网络依赖的环境下通过。
- [ ] LLM-assisted extractor 只能通过显式 provider / profile / mode 启用。
- [ ] LLM 输出必须经过 schema validation，非法 JSON、缺少 fact type、缺少 summary、缺少 evidence 等情况会清晰失败。
- [ ] LLM 输出必须经过 sanitization，敏感信息不能进入 Long-term Memory。
- [ ] LLM 输出必须经过 Quality Gate，不能绕过 Issue 02 的拒绝规则。
- [ ] LLM 输出必须经过 Issue 03 / Issue 04 的 dedup、merge 和 conflict guard。
- [ ] LLM-assisted 模式的错误信息不泄漏 API key、Authorization header、token 或 prompt 中的敏感数据。
- [ ] 自动化测试使用 fake LLM provider、stub response 或 mock client，不调用真实外部 LLM。
- [ ] 有测试覆盖合法 LLM 输出、非法 schema、缺字段、敏感内容、低质量事实和默认隔离边界。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
- `02-memory-fact-quality-gate-pollution-guard.md`
