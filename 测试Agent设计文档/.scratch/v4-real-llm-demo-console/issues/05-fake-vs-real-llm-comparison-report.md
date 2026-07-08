Status: ready-for-agent

# V4 Issue 05：Fake vs Real LLM 对比报告

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

实现同一 fixture 下 fake baseline 与 real LLM run 的对比报告能力。用户可以在 comparison 模式下运行一次对比，系统先保留稳定 fake baseline，再展示真实 LLM run 与 baseline 的差异。

对比报告不应宣称真实 LLM 一定更好，而是帮助用户解释模型行为差异、风险点和人工判断点。报告需要覆盖 plan、tool selection、failure classification、root cause、next suggestion、memory feedback 和最终演示摘要，并输出 JSON 与 Markdown artifact。

## Acceptance criteria

- [ ] comparison 模式可以基于同一 fixture 生成 fake baseline 与 real LLM run 的对比结构。
- [ ] 对比结果包含计划步骤差异、工具调用差异、失败分析差异、memory feedback 差异和报告摘要差异。
- [ ] 对比报告明确标记 fake baseline 和 real run 的 provider 来源。
- [ ] 对比报告输出 JSON artifact，并包含 `runId`、`schemaVersion`、`fixtureId`、provider 信息和 artifact references。
- [ ] 对比报告输出 Markdown artifact，方便无前端时阅读和贴入文档。
- [ ] 真实 LLM 不可用时，comparison 模式能展示 fake baseline 和 real run 失败原因，而不是整体崩溃。
- [ ] comparison 模式默认不污染长期记忆。
- [ ] 有应用层测试覆盖 comparison result 的结构、artifact 引用和 real run 失败降级行为。

## Blocked by

- `01-demo-run-result-contract-and-fake-baseline.md`
- `04-real-llm-manual-experiment-mode.md`

