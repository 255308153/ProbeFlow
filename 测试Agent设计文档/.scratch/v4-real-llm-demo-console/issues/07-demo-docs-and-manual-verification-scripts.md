Status: ready-for-agent

# V4 Issue 07：Demo 文档与手动验证脚本

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

补齐 V4 的中文文档和手动验证路径，让用户和同事能从拉取项目开始，按文档启动 fake demo、配置 real LLM、运行 comparison、查看 JSON / Markdown artifact、打开 Demo Console，并理解 V4 与 V5 的边界。

文档要服务面试讲解：不仅写“怎么跑”，还要写“这次 demo 展示了 ProbeFlow 的哪些 Agent 设计”，包括受控 Planner、Tool Contract、Policy Validator、Unified Context、ExecutionContext、Failure Analysis、Memory Feedback 和 Evaluation。

## Acceptance criteria

- [ ] README 或专门 Demo 文档中包含 V4 fake demo 的启动和运行步骤。
- [ ] 文档说明 real LLM 模式需要哪些配置，以及缺配置时会如何失败。
- [ ] 文档说明 comparison 模式如何运行、报告在哪里、如何阅读 fake vs real 差异。
- [ ] 文档说明 Demo Console 如何启动和访问。
- [ ] 文档说明 JSON / Markdown artifact 的输出位置和主要字段含义。
- [ ] 文档明确 V4 不做 RAG / Memory Pro，V5 才做真实 embedding、pgvector、多路召回、rerank、Small-to-Big、自研 Memory Engine 和 Context Engine 强化。
- [ ] 文档明确不直接依赖 mem0 或 VikingDB，只借鉴其思想并在后续自研。
- [ ] 存在手动验证脚本或清晰命令，覆盖 fake、real 配置校验、comparison 或其可替代路径。

## Blocked by

- `02-demo-run-api-local-manual-entrypoint.md`
- `03-demo-console-first-runnable-screen.md`
- `04-real-llm-manual-experiment-mode.md`
- `05-fake-vs-real-llm-comparison-report.md`

