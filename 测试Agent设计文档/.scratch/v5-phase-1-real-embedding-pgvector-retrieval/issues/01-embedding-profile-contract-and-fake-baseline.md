Status: ready-for-agent

# V5-1 Issue 01：Embedding Profile 契约与 Fake 默认基线

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

建立 V5-1 的 embedding profile 契约，并保证 fake embedding 仍是默认、稳定、可测试的基线。这个 slice 需要把当前 `EmbeddingService` 从“只有维度和 query/document 方法”的基础接口，扩展成能表达 provider mode、profile id、dimension、query/document 区分、prefix 策略、错误分类和默认 provider 边界的统一契约。

完成后，Knowledge、Memory、Context 后续都通过同一套 embedding 契约工作。默认本地测试和 CI 仍然使用 fake provider，不会因为 V5-1 开始接真实 embedding 而访问外部服务。

## Acceptance criteria

- [ ] embedding provider 有明确 profile 概念，能表达 fake / real、profile id、model、dimension 和配置来源。
- [ ] fake embedding 仍是默认 provider，默认测试和默认 CI 不需要真实 embedding key。
- [ ] query embedding 与 document embedding 继续通过同一 seam 区分处理。
- [ ] query prefix 和 document prefix 在契约层有明确位置，即使 fake provider 不需要真实外部调用。
- [ ] embedding dimension 在 provider 层、写入层、查询层都能被校验。
- [ ] 空文本、错误维度、空向量等基础错误能以清晰的领域错误表达。
- [ ] provider contract 测试覆盖 fake provider 的稳定性、dimension、query/document 区分和错误边界。
- [ ] 验收测试确认默认配置不会访问真实 embedding 服务。

## Blocked by

None - can start immediately
