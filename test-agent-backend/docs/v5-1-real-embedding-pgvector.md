# ProbeFlow V5-1 真实 Embedding 与 pgvector 检索

V5-1 的目标是把 ProbeFlow 的文档知识和长期记忆从“可测的 fake scoring”推进到“真实语义向量化 + pgvector 候选召回 + 可解释上下文消费”。

核心链路：

```text
Knowledge Ingest / Memory Refinery
-> EmbeddingService
-> FakeEmbeddingService 或 ManualRealEmbeddingProvider
-> KnowledgeChunk / LongTermMemory vector(1024)
-> pgvector HNSW candidate retrieval
-> KnowledgeRetrievalApplicationService / LongTermMemoryRetrievalService
-> UnifiedContextBuilder
```

V5-1 只做地基：真实 embedding profile、pgvector 检索路径、profile 元数据、reindex guard、语义证据透传。Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank、Small-to-Big、Memory fact extraction、Memory entity graph 都不在本阶段。

## Fake Embedding 与 Real Embedding

默认 provider 是 `FakeEmbeddingService`。它使用确定性 fake embedding，适合本地开发、单元测试、CI 和演示 baseline：

- 不需要真实 embedding key。
- 不访问真实 embedding endpoint。
- 文档向量和查询向量仍通过同一个 `EmbeddingService` seam 生成，保证代码路径稳定可测。
- 默认维度是 `1024`，与数据库 `vector(1024)` schema 对齐。

真实 provider 是 `ManualRealEmbeddingProvider`。它只在显式开启 `probeflow.embedding.manual-real.enabled=true` 时成为 primary provider：

- 仍然通过 `EmbeddingService` seam 接入。
- 查询侧走 `embedQuery`，文档/记忆写入走 `embedDocument`。
- 支持 query/document prefix，用于 BGE 类模型的查询指令和文档指令分离。
- 返回向量会校验空向量、维度、非数字值和超长输入。
- 错误会分类为 missing config、timeout、remote error、invalid response、dimension mismatch、empty vector 等，不应泄漏 key、token 或 Authorization header。

默认路径不会在真实 provider 失败时悄悄 fallback 到 fake。真实 profile 是手动实验能力，不是 CI 默认能力。

## Real Embedding Profile 配置

配置位置在 `application.yml` 的 `probeflow.embedding.manual-real`，也可用环境变量覆盖。

```bash
export PROBEFLOW_EMBEDDING_MANUAL_REAL_ENABLED=true
export PROBEFLOW_EMBEDDING_MANUAL_REAL_PROFILE_ID=manual-real-embedding
export PROBEFLOW_EMBEDDING_MANUAL_REAL_ENDPOINT=https://example.local/embeddings
export PROBEFLOW_EMBEDDING_MANUAL_REAL_KEY=replace-with-local-secret
export PROBEFLOW_EMBEDDING_MANUAL_REAL_MODEL=bge-large-zh
export PROBEFLOW_EMBEDDING_MANUAL_REAL_DIMENSION=1024
export PROBEFLOW_EMBEDDING_MANUAL_REAL_TIMEOUT_MS=30000
export PROBEFLOW_EMBEDDING_MANUAL_REAL_MAX_INPUT_TOKENS=8192
export PROBEFLOW_EMBEDDING_MANUAL_REAL_QUERY_PREFIX="Represent this sentence for searching relevant passages: "
export PROBEFLOW_EMBEDDING_MANUAL_REAL_DOCUMENT_PREFIX=""
export PROBEFLOW_EMBEDDING_MANUAL_REAL_BATCH_SIZE=16
export PROBEFLOW_EMBEDDING_MANUAL_REAL_FAILURE_POLICY=fail-fast
```

字段含义：

- `profile-id`：写入 metadata 的 profile 标识，用于追踪向量来源。
- `endpoint`：兼容 embedding HTTP API 的 endpoint。
- `key`：本地 secret，不要写入仓库。
- `model`：真实 embedding 模型名。
- `dimension`：真实 provider 返回维度，当前必须与 `vector(1024)` 和 `probeflow.embedding.dimension` 对齐。
- `timeout-ms`：单次请求超时边界。
- `max-input-tokens`：粗略输入 token 上限，超限会清晰失败。
- `query-prefix`：查询侧 instruction prefix。
- `document-prefix`：文档/记忆写入侧 instruction prefix。
- `batch-size`：手动 profile 的批量配置边界，当前 provider 请求仍以单条输入为主。
- `failure-policy`：失败策略，V5-1 使用 `fail-fast` 语义。

## 默认测试与 CI 边界

默认测试使用 fake embedding 和 H2 测试库：

```bash
cd test-agent-backend
mvn test
```

这条命令不得要求真实 embedding key，也不得访问真实外部 embedding HTTP 服务。真实 provider 的自动化测试使用 fake client 或 mock response：

```bash
cd test-agent-backend
mvn -Dtest=FakeEmbeddingServiceTests,ManualRealEmbeddingProviderIssue02Tests test
```

Knowledge 与 Memory 的 pgvector 读取 seam 可用定向测试验证：

```bash
cd test-agent-backend
mvn -Dtest=KnowledgeRetrievalPgvectorIssue04Tests,LongTermMemoryPgvectorIssue05Tests,UnifiedContextSemanticEvidenceIssue06Tests test
```

## 手动 Real Profile 验证路径

手动验证真实 embedding 时，先启动 PostgreSQL + pgvector 和 Redis：

```bash
cd test-agent-backend
docker compose up -d
```

再显式设置真实 profile 环境变量，启动后端：

```bash
export PROBEFLOW_EMBEDDING_MANUAL_REAL_ENABLED=true
export PROBEFLOW_EMBEDDING_MANUAL_REAL_ENDPOINT=https://example.local/embeddings
export PROBEFLOW_EMBEDDING_MANUAL_REAL_KEY=replace-with-local-secret
export PROBEFLOW_EMBEDDING_MANUAL_REAL_MODEL=bge-large-zh
export PROBEFLOW_EMBEDDING_MANUAL_REAL_DIMENSION=1024
export PROBEFLOW_EMBEDDING_MANUAL_REAL_TIMEOUT_MS=30000
export PROBEFLOW_EMBEDDING_MANUAL_REAL_MAX_INPUT_TOKENS=8192
export PROBEFLOW_EMBEDDING_MANUAL_REAL_QUERY_PREFIX="Represent this sentence for searching relevant passages: "
export PROBEFLOW_EMBEDDING_MANUAL_REAL_BATCH_SIZE=16
export PROBEFLOW_EMBEDDING_MANUAL_REAL_FAILURE_POLICY=fail-fast

mvn spring-boot:run
```

然后通过现有 Knowledge ingest / Memory refine / retrieval 调用路径写入文档知识或长期记忆，再触发 `KnowledgeRetrievalApplicationService` 与 `LongTermMemoryRetrievalService` 读取。检查返回结果中的字段：

- `metadata.embeddingProfile.providerMode = REAL`
- `metadata.embeddingProfile.profileId = manual-real-embedding`
- `metadata.retrievalChannel = pgvector`
- `metadata.vectorDistance`
- `metadata.candidateRank`
- `metadata.reindexRequired = false`
- `matchReasons` 包含 `semantic-match`、结构化匹配或 stage/tag 命中原因

如果真实服务不可用，应看到明确的 embedding failure，而不是空结果或静默 fallback。

## pgvector 的作用

V5-1 中，`KnowledgeChunk` 与 `LongTermMemory` 都保存 `vector(1024)`：

- `knowledge_chunk.embedding vector(1024)`
- `long_term_memory.embedding vector(1024)`

PostgreSQL migration 会启用 pgvector，并在两张表上建立 HNSW index。读取时不再把所有 active chunk/memory 拉到 Java 层全量扫描，而是先用 pgvector 做候选召回，再叠加结构化过滤和可解释排序。

Knowledge RAG 使用 pgvector 召回文档 chunk，同时保留：

- document revision citation
- source ref
- document type / authority
- system、module、biz entity
- api path / HTTP method / stage / tags
- component scores
- match reasons
- low confidence

Long-term Memory 使用 pgvector 召回历史经验，同时保留：

- scope type
- source ref
- confidence / importance / success contribution
- error code、api path、stage、tags
- component scores
- match reasons
- hitCount / lastUsedAt 更新
- low confidence

Unified Context 不直接关心具体 provider。它只消费 Knowledge 和 Memory 检索结果，并通过 `ContextBundle` 与 `ContextCitation.evidence` 把 `embeddingProfile`、`retrievalChannel`、`vectorDistance`、`candidateRank`、component scores、match reasons、lowConfidence 等证据透传给 Agent、Demo Console 和报告层。

## Embedding Profile 与 Reindex Required

每次写入 Knowledge Chunk 或 Long-term Memory 时，系统会把当前 embedding profile 写入 metadata：

```json
{
  "embeddingProfile": {
    "profileId": "fake-default",
    "providerMode": "FAKE",
    "model": "deterministic-sha256-v1",
    "dimension": 1024,
    "configSource": "application-default",
    "queryPrefix": "Represent this sentence for searching relevant passages: ",
    "documentPrefix": ""
  },
  "embeddingProfileMismatch": false,
  "reindexRequired": false
}
```

读取时会把存量向量的 profile 与当前 provider profile 对比：

- 一致：`reindexRequired=false`，向量语义空间可继续使用。
- 不一致：`embeddingProfileMismatch=true`、`reindexRequired=true`、`reindexReason=embedding-profile-mismatch`，表示这条旧向量需要重新 embedding 后再稳定参与语义检索。

这能防止 fake 向量、旧模型向量和新模型向量被静默混用。

## 范围边界

V5-1 不做：

- Query Rewrite
- 多路召回编排
- RRF
- Cross-Encoder rerank
- LLM rerank
- Small-to-Big 父子索引增强
- Memory fact extraction 完整版
- Memory entity graph
- 生产级 embedding 任务队列
- 多模型混合向量空间
- 向量维度在线迁移

这些能力属于后续 V5-2 到 V5-6。V5-1 的价值是先把真实 embedding、profile 元数据、pgvector candidate retrieval、可解释证据透传做稳。

## mem0 / VikingDB 边界

V5-1 不直接依赖 mem0 SDK 或 VikingDB。

ProbeFlow 可以借鉴外部系统的思想，例如 memory extraction、dedup、entity graph、向量索引和检索评估，但实现仍放在自研的 Memory / RAG / Context Engine 内：

- RAG 管人类写好的文档知识。
- Memory 管 Agent 运行后沉淀的经验。
- Unified Context Builder 负责把 API context、Knowledge RAG、Long-term Memory、Task Memory、Session Memory 组装成 Agent 可消费的上下文。

这样面试和团队协作时可以讲清楚 ProbeFlow 的 Agent 设计，而不是把核心能力隐藏在第三方 SDK 后面。
