Status: ready-for-agent

# V5-1 Issue 08：V5-1 Acceptance Boundary Guard

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

为 V5-1 增加验收边界保护，确保本阶段完成的是“真实 embedding provider + pgvector 检索地基”，不会悄悄扩展成 V5-4/V5-5 的高级 RAG，也不会引入默认 CI 外部依赖或直接接入 mem0/VikingDB。

该 issue 是 V5-1 的收口保护。它应验证 fake 默认基线仍稳定、真实 embedding 只在显式 profile 下启用、pgvector schema 和 HNSW index 仍存在、Knowledge / Memory 召回路径具备向量召回能力，同时明确后续高级能力仍在 out of scope。

## Acceptance criteria

- [ ] 验收测试确认默认 provider 仍为 fake，默认 `mvn test` 不需要真实 embedding key。
- [ ] 验收测试确认默认测试不会访问真实外部 embedding 服务。
- [ ] 验收测试确认 pgvector extension、`vector(1024)` 字段和 HNSW index 仍存在。
- [ ] 验收测试确认 Knowledge RAG 和 Long-term Memory 具备 pgvector 候选召回路径或等价可验证能力。
- [ ] 验收测试确认项目没有直接引入 mem0 SDK 或 VikingDB 依赖。
- [ ] 验收测试确认 V5-1 没有实现 Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank 或 Small-to-Big。
- [ ] 验收测试确认默认路径不会把真实 provider 失败静默 fallback 成 fake，除非 profile 明确允许。
- [ ] 完整 `mvn test` 在无真实 embedding 配置环境下通过。

## Blocked by

- `01-embedding-profile-contract-and-fake-baseline.md`
- `02-real-embedding-provider-manual-experiment-mode.md`
- `03-vector-write-profile-metadata-and-reindex-guard.md`
- `04-knowledge-rag-pgvector-candidate-retrieval.md`
- `05-long-term-memory-pgvector-candidate-retrieval.md`
- `06-unified-context-semantic-evidence-propagation.md`
- `07-v5-1-docs-and-manual-verification.md`
