状态：ready-for-agent

# Issue 04：LLM call logging persistence and migration

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

增加 LLM 调用审计持久化能力。每次 LLM 调用无论成功、失败还是被 policy 拦截，都应该能形成可追踪的调用日志。日志需要记录 provider、model、template、request hash、状态、错误类型、耗时、token 用量、task id、plan step id、用途等信息。

这个 issue 只做持久化模型、repository、迁移和查询能力，不负责完整调用流程。

## Acceptance criteria

- [ ] 新增 LLM 调用日志持久化模型。
- [ ] 新增数据库迁移脚本。
- [ ] 新增 LLM 调用日志 repository。
- [ ] 日志可以记录 task id 和 plan step id。
- [ ] 日志可以记录 purpose、provider、model、template id、template version。
- [ ] 日志可以记录 request hash。
- [ ] 日志可以记录 status 和 error type。
- [ ] 日志可以记录 latency 和 token usage。
- [ ] 日志可以保存裁剪后的 prompt/response 摘要字段。
- [ ] 日志不包含 API key 或敏感 header 字段。
- [ ] Repository 支持按状态、task id、用途查询或为这些查询预留清晰方法。
- [ ] 测试覆盖迁移、保存和基础查询。

## Blocked by

- Issue 01：LLM domain model and provider abstraction
