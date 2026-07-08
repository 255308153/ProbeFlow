Status: ready-for-agent

# V4 Issue 08：V4 Acceptance Boundary Guard

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

为 V4 增加验收边界保护，确保本阶段完成的是“真实 LLM 手动实验 + Demo Console”，不会悄悄变成完整商业前端、生产后台、真实外部 HTTP 执行、默认 CI 真实 LLM、mem0/VikingDB 直接依赖或 V5 RAG Pro。

该 issue 是 V4 的收口保护。它应把前面 issues 的核心行为串起来验证：fake baseline 可稳定运行，real LLM 在无配置时清晰失败，Demo API / Console 可用于手动演示，comparison 默认不污染长期记忆，所有默认测试不依赖外部模型。

## Acceptance criteria

- [ ] 有 acceptance boundary guard 测试或等价验收测试覆盖 V4 允许新增 Demo API 和 Demo Console。
- [ ] 验收测试确认默认 CI / 默认测试不需要真实 LLM key，不访问真实外部模型。
- [ ] 验收测试确认默认 demo 不访问真实外部业务 HTTP，`usesExternalHttp` 或等价字段保持可见。
- [ ] 验收测试确认项目没有直接引入 mem0 SDK 或 VikingDB 依赖。
- [ ] 验收测试确认 V4 没有引入生产级登录、租户、RBAC 或完整测试管理后台。
- [ ] 验收测试确认 V4 没有实现 BM25、PG full-text、Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank 或 Small-to-Big 等 V5 RAG Pro 能力。
- [ ] 验收测试确认 comparison 模式默认不污染长期记忆。
- [ ] 完整 `mvn test` 在无真实 LLM 配置环境下通过。

## Blocked by

- `01-demo-run-result-contract-and-fake-baseline.md`
- `02-demo-run-api-local-manual-entrypoint.md`
- `03-demo-console-first-runnable-screen.md`
- `04-real-llm-manual-experiment-mode.md`
- `05-fake-vs-real-llm-comparison-report.md`
- `06-demo-redaction-memory-write-switch-and-safety-boundaries.md`
- `07-demo-docs-and-manual-verification-scripts.md`

