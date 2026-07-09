# V5-5 Rerank / Small-to-Big 收口说明

V5-5 是 V5 读路径质量收口阶段。主链路是：

```text
multi-route candidates -> rerank -> Small-to-Big -> Unified Context
```

V5-4 负责把任务上下文改写成 query variants，并通过 Knowledge / Memory / Graph / exact entity 等 route 扩大候选覆盖。V5-5 不重新召回，也不修改这些 route recall 规则；它只消费已经带有 route evidence、fused score、before rank 和候选身份的 multi-route candidates。

## Rerank 默认路径

deterministic rerank 是默认路径。它使用可解释 feature ledger 对候选做稳定排序，重点看：

- 当前 stage fit；
- route agreement 和 route diversity；
- apiPath、errorCode、variableKey、policyReason 等 exact entity；
- semantic / metadata / lexical relevance；
- 文档 authority 与 freshness；
- memory confidence、importance、success contribution；
- graph confidence 与 path length；
- token cost、low confidence 和 conflict signals。

默认测试和 CI 只验证 deterministic baseline、fake provider 或本地 stub。默认测试不能依赖真实 LLM，也不能依赖真实 DeepSeek key、外部网络、Cross Encoder 服务、外部 rerank 服务或外部图数据库。

Cross Encoder / LLM rerank 是显式 profile。Cross Encoder 只是 adapter contract，默认 `enabled=false`、`provider=disabled`；LLM rerank 也默认关闭，结构化输出校验失败时必须回退到 deterministic rerank。

DeepSeek V4 Pro 保留为内部试用真实 LLM 手动验收路径。启用它必须显式打开 rerank LLM profile 和 manual real LLM provider policy，并记录候选 fixture、模型响应摘要、fallback 状态和最终排序。fake LLM 不能作为 internal alpha 唯一验收，它只是无 key / CI 环境的 fallback。

## Small-to-Big 扩展

Rerank 之后才做 Small-to-Big。这样扩展预算优先给真正有价值的材料，而不是给 V5-4 初始 fused rank 中看似相关但低价值的材料。

Knowledge Small-to-Big 以 anchor `KnowledgeChunk` 为中心，扩展到：

- 父章节；
- 错误码条目；
- 测试规范条目或同规则组；
- business flow 相邻步骤；
- 必要的 revision summary。

Memory Small-to-Big 以 memory hit、fact fingerprint 或 graph relation 为中心，扩展到：

- full content；
- evidence summaries；
- merged source refs；
- identity hints；
- graph relation explanation；
- 明确标记的 conflict audit。

Small-to-Big 不改写事实来源。Memory Refinery 仍然是长期记忆写入口，V5-5 只读取长期记忆、evidence ledger 和 graph relation evidence，并把它们作为 citation evidence 送入 Unified Context。

## 离线评估 fixture

Issue 08 的最小评估 fixture 用固定候选池比较 V5-4 fused rank 和 V5-5 post-rerank rank。覆盖两个场景：

- `case_generation`：旧的分页说明在 V5-4 fused rank 靠前，但因为过时、冲突和 token cost 高被下调；当前测试规范条目因为 stage fit、exact entity、route agreement 和 authority 被提升。
- `failure_analysis`：旧 PAY_401 runbook 在 lexical fused rank 靠前，但因为低置信、冲突和过高 token cost 被下调；当前错误码条目因为 errorCode 精确命中、route agreement、freshness 和 authority 被提升。

同一个 fixture 也验证 Small-to-Big：

- 测试规范 anchor 扩展到同规则组；
- 错误码 anchor 扩展到完整错误码条目；
- memory evidence 扩展出 evidence summaries、merged source refs 和 graph relation explanation。

这些 fixture 不调用真实模型、真实网络或外部数据库，适合作为默认验收。

## V5 完成边界

V5 完成边界是：V5-1 到 V5-5 形成 RAG / Memory / Context Engine 闭环。

```text
V5-1: Real Embedding + pgvector Retrieval
V5-2: Memory Fact Extraction + Dedup
V5-3: Memory Entity Graph
V5-4: Query Rewrite + Multi-route Retrieval
V5-5: Rerank + Small-to-Big
```

完成后，ProbeFlow 的读写链路可以这样解释：

```text
文档知识和运行经验进入真实语义检索地基；
运行经验通过 Memory Refinery 提纯成长期事实；
长期事实投影成 Memory Entity Graph；
读路径通过 Query Rewrite 和 Multi-route Retrieval 扩大候选覆盖；
候选再经过 Rerank 和 Small-to-Big 变成可解释、完整、预算受控的 Unified Context。
```

V5-5 不改变 V5-4 route recall，不替换 Memory Refinery，不引入 mem0 SDK、VikingDB、Neo4j 或新的外部数据库。V6-1 才进入本地项目导入；本地文件夹项目导入、Git clone、zip 上传和前端项目导入控制台都不属于 V5 收口范围。
