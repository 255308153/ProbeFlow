Status: ready-for-agent

# V5-1 Issue 04：Knowledge RAG pgvector 候选召回

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

将 Knowledge RAG 的读取路径升级为 pgvector 候选召回。`KnowledgeRetrievalApplicationService` 应使用 query embedding 和 pgvector 找到语义候选，再与 systemName、moduleName、bizEntity、apiPath、httpMethod、documentType、applicableStage、tags、document status、revision latest 等结构化条件协同。

完成后，RAG 文档量增加时不需要把所有 active chunk 拉到应用层全量扫描。检索结果仍然需要可解释：vector distance 只是 component score 之一，citation、token budget、lowConfidence、match reasons 和 metadata filter 都必须保留。

## Acceptance criteria

- [ ] Knowledge Retrieval 使用 pgvector 进行候选召回，而不是依赖全量 active chunk 应用内扫描。
- [ ] 检索仍遵守 systemName、moduleName、bizEntity、apiPath、httpMethod、documentType、applicableStage、tags 等条件。
- [ ] 检索不会召回 superseded chunk、非 latest revision 或 inactive document 的内容。
- [ ] 检索结果包含 vectorDistance 或等价语义分数。
- [ ] 检索结果保留 component scores，并能表达 vector、metadata/structure、keyword、authority、freshness、stageFit 等贡献。
- [ ] 检索结果保留 match reasons、lowConfidence、coverage、candidateRank 或等价排序解释。
- [ ] citation 继续包含 documentRevisionId、sourceRef、chunkId 等可追溯信息。
- [ ] limit 和 token budget 继续生效。
- [ ] 有应用层测试覆盖语义候选、结构化过滤、latest/status 过滤、citation、budget 和解释字段。

## Blocked by

- `01-embedding-profile-contract-and-fake-baseline.md`
- `03-vector-write-profile-metadata-and-reindex-guard.md`
