状态：ready-for-agent

# Issue 05：LlmApplicationService success and failure flow

## Parent

V2 Phase 1：LLM Provider 与调用审计 PRD

## What to build

实现 V2 Phase 1 的最高层 LLM 调用入口。调用方通过应用服务发起 LLM 调用，服务负责解析 prompt template、渲染变量、选择 provider、执行调用、归一化响应或错误、写入调用日志，并返回结构化调用结果。

这个 issue 应把前面 provider、prompt、logging 串成一条完整可验证的垂直路径。

## Acceptance criteria

- [ ] 新增 `LlmApplicationService` 或等价最高层应用服务。
- [ ] 应用服务可以接收结构化 LLM 调用请求。
- [ ] 应用服务可以解析 prompt template。
- [ ] 应用服务可以渲染 prompt 变量。
- [ ] 应用服务可以选择并调用 provider。
- [ ] 成功调用返回结构化结果。
- [ ] 成功调用写入成功日志。
- [ ] provider 失败返回结构化失败结果。
- [ ] provider 失败仍然写入失败日志。
- [ ] prompt 渲染失败不会调用 provider。
- [ ] prompt 渲染失败写入或返回明确 TEMPLATE_RENDER_ERROR。
- [ ] provider/model/options/purpose/template version/request hash 被正确记录。
- [ ] latency 和 token usage 被归一化记录。
- [ ] 测试通过应用服务覆盖成功、失败和渲染错误路径。

## Blocked by

- Issue 01：LLM domain model and provider abstraction
- Issue 02：Fake LLM Provider for deterministic CI
- Issue 03：Prompt template registry and rendering validation
- Issue 04：LLM call logging persistence and migration
