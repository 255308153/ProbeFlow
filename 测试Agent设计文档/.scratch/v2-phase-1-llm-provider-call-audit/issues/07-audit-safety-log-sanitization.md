状态：ready-for-agent

# Issue 07：Audit safety and log sanitization

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

加强 LLM 调用审计的安全性和存储友好性。调用日志应该能帮助追踪模型行为，但不能泄露 API key、敏感 header 或无限制保存超长 prompt/response。系统应保存 request hash、摘要和裁剪内容，并为未来调试保留可控扩展点。

这个 issue 让 LLM 调用审计既有用，又不会成为安全和存储风险。

## Acceptance criteria

- [ ] 调用日志不会保存 API key。
- [ ] 调用日志不会保存敏感 header。
- [ ] prompt 摘要有长度上限。
- [ ] response 摘要有长度上限。
- [ ] 超长 prompt/response 会被稳定裁剪。
- [ ] request hash 使用稳定算法生成。
- [ ] 日志保留足够信息用于审计 provider、model、template、purpose、status 和 error。
- [ ] 测试覆盖敏感信息不入库。
- [ ] 测试覆盖超长 prompt/response 裁剪。
- [ ] 测试覆盖 request hash 稳定性。

## Blocked by

- Issue 04：LLM call logging persistence and migration
- Issue 05：LlmApplicationService success and failure flow
