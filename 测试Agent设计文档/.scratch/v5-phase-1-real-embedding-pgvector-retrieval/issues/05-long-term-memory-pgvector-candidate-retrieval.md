Status: ready-for-agent

# V5-1 Issue 05：Long-term Memory pgvector 候选召回

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

将 Long-term Memory 的读取路径升级为 pgvector 候选召回。`LongTermMemoryRetrievalService` 应使用 query embedding 和 pgvector 找到语义候选，再与 scopeType、systemName、moduleName、apiPath、errorCode、tags、stageProfile、memory status 等结构化条件协同。

完成后，长期记忆数量增长时不会靠全量 active memory 扫描来做语义排序。召回结果仍然需要服务 Agent 可解释决策：保留 vector distance、component scores、match reasons、lowConfidence、token budget，并继续更新 hitCount 和 lastUsedAt。

## Acceptance criteria

- [ ] Long-term Memory Retrieval 使用 pgvector 进行候选召回，而不是依赖全量 active memory 应用内扫描。
- [ ] 检索仍遵守 scopeType、systemName、moduleName、apiPath、errorCode、tags、stageProfile 等条件。
- [ ] 检索不会召回 archived、inactive 或 profile mismatch 且不可用的 memory。
- [ ] 检索结果包含 vectorDistance 或等价语义分数。
- [ ] 检索结果保留 component scores，并能表达 vector、structure、tag、importance、confidence、successContribution、hitCount、freshness、stageFit 等贡献。
- [ ] 检索结果保留 match reasons、lowConfidence、candidateRank 或等价排序解释。
- [ ] limit 和 token budget 继续生效。
- [ ] 被选中的 memory 继续更新 hitCount 和 lastUsedAt。
- [ ] 有应用层测试覆盖语义候选、scope/status/errorCode 过滤、budget、hitCount 更新和解释字段。

## Blocked by

- `01-embedding-profile-contract-and-fake-baseline.md`
- `03-vector-write-profile-metadata-and-reindex-guard.md`
