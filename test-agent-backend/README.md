# ProbeFlow Test Agent Backend

Spring Boot backend foundation for the ProbeFlow HTTP API Test Agent.

## Requirements

- Java 21
- Maven 3.9+
- Docker Compose

## Local Infrastructure

Start PostgreSQL with pgvector and Redis:

```bash
docker compose up -d
```

The default application settings expect:

- PostgreSQL: `jdbc:postgresql://localhost:5432/probeflow_test_agent`
- Redis: `localhost:6379`

## Run

```bash
mvn spring-boot:run
```

Flyway runs on application startup. The default profile applies common migrations and the PostgreSQL pgvector extension migration.

## Test

```bash
mvn test
```

Automated startup tests use the `test` profile with an in-memory H2 database, so they do not require locally running containers.

## V5-4 Query Rewrite And Multi-route Retrieval

V5-4 expands the read path from one raw query to deterministic query variants, stage-aware Knowledge / Memory / Graph routes, route fusion, budget fallback, and Unified Context route evidence. It covers recall and explanation; V5-5 owns rerank and Small-to-Big.

See [docs/v5-4-query-rewrite-multi-route-retrieval.md](docs/v5-4-query-rewrite-multi-route-retrieval.md) for Chinese design notes, V5-3 / V5-5 boundaries, deterministic rewrite defaults, DeepSeek V4 Pro manual acceptance constraints, budget governance, and acceptance guardrails.

## V5-5 Cross Encoder Rerank Adapter

V5-5 keeps Cross Encoder rerank as an explicit adapter contract, not a default dependency. Default configuration disables the provider, automated tests use fake providers or local stubs, and failures fall back to deterministic rerank.

See [docs/v5-5-cross-encoder-rerank-adapter.md](docs/v5-5-cross-encoder-rerank-adapter.md) for the request / response contract, safe metadata boundary, fallback behavior, and external-service guardrails.

## V5-5 LLM Rerank Manual Profile

V5-5 keeps LLM rerank behind an explicit manual profile. Default configuration disables the provider, automated tests use fake LLM or stub responses, invalid structured output falls back to deterministic rerank, and internal alpha validation must retain a real DeepSeek V4 Pro manual acceptance path.

See [docs/v5-5-llm-rerank-manual-profile.md](docs/v5-5-llm-rerank-manual-profile.md) for safe candidate summaries, JSON output validation, citation guardrails, redacted diagnostics, and DeepSeek policy.

## V5-5 Rerank Small-to-Big Completion

V5-5 closes the V5 read path by comparing V5-4 fused rank with post-rerank rank, expanding useful small hits into parent/error-code/test-spec/memory evidence context, and feeding post-rerank expanded materials into Unified Context without changing V5-4 route recall or Memory Refinery writes.

See [docs/v5-5-rerank-small-to-big-completion.md](docs/v5-5-rerank-small-to-big-completion.md) for Chinese V5 completion notes, offline rerank evaluation fixture scope, deterministic default boundaries, DeepSeek V4 Pro manual validation policy, and the V6-1 local project import boundary.

## V6-1 Local Folder Project Import

V6-1 adds a server-side local directory project import entry over existing `SourceMaterial`, `Task`, `ApiAnalysis`, and `ApiSpec`. It validates absolute paths against configured allowed roots, analyzes Spring controllers, exposes import detail and API specs, and reanalyzes while preserving historical interfaces.

See [docs/v6-1-local-folder-project-import.md](docs/v6-1-local-folder-project-import.md) for Chinese manual verification commands, allowed-root configuration, failure and multi-module blockers, scan ignore rules, DeepSeek V4 Pro internal alpha gate, and V6-1 out-of-scope boundaries.

## V5-1 Real Embedding And pgvector

V5-1 adds a real embedding manual profile, pgvector candidate retrieval for Knowledge RAG and Long-term Memory, embedding profile metadata, reindex guards, and semantic evidence propagation into Unified Context. Fake embedding remains the default for local tests and CI.

See [docs/v5-1-real-embedding-pgvector.md](/Users/lqc/Downloads/ProbeFlow/test-agent-backend/docs/v5-1-real-embedding-pgvector.md) for Chinese setup notes, manual real profile verification, default test boundaries, pgvector behavior, and V5-1 scope limits.

## V4 Demo Console

V4 adds a local Demo Run API and Demo Console over the existing Manual Suite Agent Harness. The default fake baseline is deterministic and does not require a real LLM key, real embedding, or real external HTTP.

See [docs/v4-demo-console.md](/Users/lqc/Downloads/ProbeFlow/test-agent-backend/docs/v4-demo-console.md) for fake demo startup, real LLM configuration validation, comparison reports, Demo Console access, artifact fields, and the V4/V5 boundary. A local verification shortcut is available:

```bash
./scripts/verify-v4-demo.sh
```

## V3-1 Manual Suite Agent Harness

V3-1 提供一个后端本地演示入口，用固定 fixture、deterministic fake provider 和 fake HTTP gateway 运行 Manual Suite Agent Harness。默认不会调用真实 LLM、真实 embedding 或真实外部 HTTP。

默认运行订单链路 demo：

```bash
mvn -q -DskipTests exec:java
```

指定 fixture 和输出目录：

```bash
mvn -q -DskipTests exec:java -Dexec.args="--fixture-id=order-suite-demo --output-dir=target/v3-manual-suite-agent"
```

可用 fixture：

- `order-suite-demo`：订单创建、支付、查询三步 fake HTTP 链路。
- `v3-smoke`：最小 smoke fixture。

运行完成后，命令会打印：

- `jsonReport=...`
- `markdownReport=...`
- `usesRealLlm=false`
- `usesExternalHttp=false`

Manual real LLM 模式必须显式传入 `--provider-mode=MANUAL_REAL_LLM --allow-manual-real-llm`，并且仍受 harness provider policy 约束；默认 demo 和 CI 不依赖真实 LLM。常见失败诊断包括 `FIXTURE_NOT_FOUND`、`FIXTURE_INVALID`、`INVALID_PROVIDER_MODE`、`PROVIDER_BLOCKED` 和 `REPORT_WRITE_FAILED`。

## V3-6 Memory Feedback, Agent Evaluation And Demo Loop

V3-6 把已有 V3 链路能力串成轻量闭环：Manual Suite Agent Harness 使用 deterministic fake provider 和 fake HTTP gateway 展示 generated-suite-draft、execution-result、variable-audit、failure-analysis、memory-feedback 和 evaluation-comparison。默认路径不依赖真实 LLM、真实 embedding 或真实外部 HTTP。

闭环叙事是：感知上下文、规划链路、调用工具执行、诊断失败、形成经验、评估能力、可视化演示。`memory-feedback` 通过 `AgentMemoryFeedbackApplicationService` 和 `MemoryRefineryService` 处理 V3-5 的失败分析输出；`evaluation-comparison` 通过 `AgentEvaluationApplicationService` 运行 `v3-phase-6-suite-agent-capability` 确定性评估数据集。

V3-6 只消费前序阶段产物，不重做 V3-2、V3-3、V3-4、V3-5：不重新生成 BusinessFlowCandidate，不重新生成 extractRules 或变量引用，不重新执行 VariableResolver、ResponseExtractor、VariableWriteBack，也不重新分类 suite failure、root cause、affected downstream steps 或 recovery suggestion。它也不是完整版 RAG、mem0、完整产品前端或生产级记忆治理平台。
