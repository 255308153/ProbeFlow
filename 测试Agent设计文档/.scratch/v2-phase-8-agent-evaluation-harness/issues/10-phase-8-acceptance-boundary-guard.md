状态：ready-for-agent

# Issue 10：Phase 8 acceptance boundary guard

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

补齐 V2 Phase 8 的验收边界测试，证明本阶段交付的是后端 Agent Evaluation Harness，而不是提前实现产品 UI、商业化大屏、A/B 平台、学术 benchmark、外部评测平台、队列 worker 或自由 AutoGPT 式 Agent。

这个 issue 的目标是保护 V2 Agent Core 的最后边界：Agent 可以被评估，但 Evaluation Harness 不能绕过 Planner、PolicyValidator、ToolContract、MemoryRefinery 和现有业务模块所有权。

## Acceptance criteria

- [ ] 边界测试证明 Phase 8 没有新增 REST Controller。
- [ ] 边界测试证明 Phase 8 没有新增 Web Console、前端页面或商业化评估大屏。
- [ ] 边界测试证明 Phase 8 没有实现复杂 A/B 平台、在线实验系统或生产流量评分。
- [ ] 边界测试证明 Phase 8 没有接入 OpenAI Evals、LangSmith、Weights & Biases、DeepEval、Ragas 或类似外部评测依赖作为必需链路。
- [ ] 边界测试证明 Phase 8 没有接入 Slack、Jira、GitHub issue、Webhook、邮件或外部通知系统。
- [ ] 边界测试证明 Phase 8 没有新增队列 worker、分布式评估任务或后台定时评估。
- [ ] 边界测试证明真实 LLM、真实 embedding、真实外部 HTTP 不是 CI 或默认回归评估依赖。
- [ ] 边界测试证明 Evaluation Harness 没有变成自由 AutoGPT-style loop。
- [ ] 边界测试证明 Evaluation Harness 不绕过 PolicyValidator 或 ToolContract。
- [ ] 边界测试证明 Planner、PolicyValidator、UnifiedContextBuilder、MemoryRefinery、FailureAnalysis、TestCaseGeneration、ReportGeneration 没有被重写为评估专用实现。
- [ ] 边界测试证明 evaluation report 和 metric diagnostic 不泄露 token、authorization、cookie、password、secret 等敏感字段。
- [ ] Phase 8 关键应用层路径有测试覆盖：evaluation foundation、planner evaluator、tool policy evaluator、context citation evaluator、failure evaluator、case coverage evaluator、report evaluator、memory reuse evaluator、regression command。
- [ ] 完整后端测试 `mvn test` 通过。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
- Issue 02：Planner decision accuracy evaluator
- Issue 03：Tool selection and policy validity evaluator
- Issue 04：Context citation usefulness evaluator
- Issue 05：Failure classification accuracy evaluator
- Issue 06：Test case coverage evaluator
- Issue 07：Report usefulness and no-secret evaluator
- Issue 08：Memory reuse closed-loop evaluator
- Issue 09：Regression evaluation command, thresholds and weighting
