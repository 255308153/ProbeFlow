Status: ready-for-agent

# V5-1 Issue 06：Unified Context 语义证据透传

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

让 Unified Context 能消费并透出 V5-1 的语义召回证据。此 slice 不重写 Context Engine，也不做 V5-6 的 Context Engine Pro；它只把 Knowledge RAG 和 Long-term Memory pgvector 召回产生的 embeddingProfile、retrievalChannel、vectorDistance、candidateRank、component scores、match reasons、citation、lowConfidence 等信息带入上下文结果。

完成后，Agent、Demo Console 和报告层可以解释“为什么这条文档知识或记忆进入上下文”，而不是只看到一段被拼进去的文本。

## Acceptance criteria

- [ ] Unified Context 的 Knowledge 部分能保留 citation、embeddingProfile、retrievalChannel、vectorDistance、candidateRank 或等价语义证据。
- [ ] Unified Context 的 Memory 部分能保留 embeddingProfile、retrievalChannel、vectorDistance、candidateRank、match reasons 或等价语义证据。
- [ ] Context coverage 能继续表达是否有 Knowledge Context、Long-term Memory、Task Memory、Session Memory。
- [ ] lowConfidence 或上下文不足信号不会在 Context 组装时丢失。
- [ ] token budget / context budget 继续生效，不因透传证据导致上下文无限增长。
- [ ] Unified Context 不直接依赖具体第三方 embedding provider。
- [ ] Demo Run 或报告可消费这些语义证据的结构化字段，但不需要在本 issue 做完整前端展示。
- [ ] 有测试覆盖 Knowledge 和 Memory 语义证据进入 ContextBundle 的外部行为。

## Blocked by

- `04-knowledge-rag-pgvector-candidate-retrieval.md`
- `05-long-term-memory-pgvector-candidate-retrieval.md`
