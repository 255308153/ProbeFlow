Status: ready-for-agent

# V4 Issue 01：Demo Run 结果契约与 Fake Baseline

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

建立 V4 Demo Run 的最高层应用入口和稳定结果契约。该 slice 需要围绕 `DemoRunApplicationService` 打通一条可验证的 fake baseline：输入一个 demo fixture 和 fake provider profile 后，系统复用现有 Manual Suite Agent Harness，产出前端和脚本都能消费的结构化 demo result。

本 issue 的重点不是做真实 LLM 或前端，而是先把 V4 的结果模型固定下来。结果需要能够表达一次演示运行的 run summary、provider summary、plan、context、tool calls、suite draft、execution、variable audit、failure analysis、memory feedback、evaluation comparison 和 artifact references。后续 API、Console、真实 LLM、对比报告都应消费这个契约，而不是各自拼装一套结构。

## Acceptance criteria

- [ ] 存在一个最高层 `DemoRunApplicationService` 或等价应用服务，作为 V4 demo run 的主要测试 seam。
- [ ] 给定内置 demo fixture 和 fake provider profile，可以返回 completed demo result。
- [ ] Demo result 包含 `runId`、`schemaVersion`、`fixtureId`、`providerMode`、`usesRealLlm`、`usesExternalHttp` 等演示元信息。
- [ ] Demo result 至少包含 Plan、Context、Tools、Suite、Execution、Variable Audit、Failure Analysis、Memory Feedback、Evaluation 这些核心区块。
- [ ] Demo result 不直接暴露 harness 内部领域对象，而是返回稳定 view model / DTO。
- [ ] Fake baseline 路径默认不依赖真实 LLM key、真实 embedding 或真实外部 HTTP。
- [ ] 该 slice 有应用层测试覆盖 fake baseline completed 路径和核心区块存在性。
- [ ] 完整测试在无真实 LLM 配置的环境下仍可通过。

## Blocked by

None - can start immediately

