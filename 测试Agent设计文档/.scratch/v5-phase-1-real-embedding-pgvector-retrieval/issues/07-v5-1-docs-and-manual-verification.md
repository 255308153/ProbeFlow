Status: ready-for-agent

# V5-1 Issue 07：V5-1 文档与手动验证路径

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

补齐 V5-1 的中文文档和手动验证路径，让用户和同事能理解 fake embedding、real embedding、pgvector 召回、profile 元数据和默认测试边界之间的关系。

文档需要服务面试讲解：用户要能说明 ProbeFlow 为什么不直接依赖 mem0/VikingDB，为什么 V5-1 先做真实 embedding 与 pgvector，为什么 Query Rewrite、rerank、Small-to-Big 留到后续，以及如何在本地用 fake 模式稳定测试、用 real profile 手动实验。

## Acceptance criteria

- [ ] README 或专门 V5 文档说明 fake embedding 与 real embedding 的区别。
- [ ] 文档说明真实 embedding profile 的配置项，包括 endpoint、model、dimension、prefix、timeout、batch size、failure policy。
- [ ] 文档说明默认测试和 CI 不访问真实 embedding 服务。
- [ ] 文档说明如何手动运行 real embedding profile 的验证路径。
- [ ] 文档说明 pgvector 在 Knowledge RAG 和 Long-term Memory 中的作用。
- [ ] 文档说明 embedding profile / reindex required 的意义。
- [ ] 文档明确 V5-1 不做 Query Rewrite、多路召回、rerank、Small-to-Big、Memory fact extraction、Memory entity graph。
- [ ] 文档明确不直接依赖 mem0 SDK 或 VikingDB，只借鉴其思想并自研 Memory / RAG / Context Engine。

## Blocked by

- `02-real-embedding-provider-manual-experiment-mode.md`
- `04-knowledge-rag-pgvector-candidate-retrieval.md`
- `05-long-term-memory-pgvector-candidate-retrieval.md`
