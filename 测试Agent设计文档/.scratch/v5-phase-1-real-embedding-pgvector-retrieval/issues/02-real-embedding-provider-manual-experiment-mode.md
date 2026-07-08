Status: ready-for-agent

# V5-1 Issue 02：Real Embedding Provider 手动实验模式

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

新增真实 embedding provider 的手动实验模式。真实 provider 必须挂在 Issue 01 的 embedding 契约后面，只能通过显式 profile 启用，不能成为默认测试路径。

该 slice 的目标是让用户可以在本地配置真实 embedding endpoint / model / key / timeout / prefix 后手动验证请求构造和响应解析，同时保证默认 `mvn test` 不调用外部 embedding 服务。真实 provider 的自动化测试必须使用 fake client、mock server 或等价测试替身。

## Acceptance criteria

- [ ] 存在 real embedding provider adapter，并实现统一 embedding 契约。
- [ ] 真实 provider 只能通过显式 profile / 配置启用，默认 provider 仍是 fake。
- [ ] 真实 provider 支持 endpoint、model、dimension、timeout、max input tokens、query prefix、document prefix、batch size、failure policy 等配置。
- [ ] 查询侧能使用 query instruction prefix，文档侧不会被错误查询 prefix 污染。
- [ ] 缺少 endpoint、model、key、dimension 等配置时，返回清晰配置错误。
- [ ] timeout、remote error、invalid response、empty vector、dimension mismatch 等错误被分类。
- [ ] 错误信息和日志不泄漏 API key、Authorization header、token 或敏感 endpoint 参数。
- [ ] 自动化测试使用 fake client / mock server，不调用真实外部 embedding 服务。

## Blocked by

- `01-embedding-profile-contract-and-fake-baseline.md`
