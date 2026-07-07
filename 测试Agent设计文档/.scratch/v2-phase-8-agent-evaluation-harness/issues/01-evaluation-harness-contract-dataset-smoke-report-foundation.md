状态：ready-for-agent

# Issue 01：Evaluation harness contract, dataset and smoke report foundation

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

建立 Agent Evaluation Harness 的最小可运行闭环。系统需要有统一的应用层入口，可以加载一个 golden dataset，运行一个 smoke fixture，生成 `EvaluationRun`、`EvaluationCaseResult`、`EvaluationMetricResult` 和结构化 `EvaluationReport`。

这个 issue 只交付评估框架的契约、数据集/fixture/run/result/report 基础，以及一个最小 smoke path。不实现各专项 evaluator 的完整评分逻辑。

## Acceptance criteria

- [ ] 新增 `AgentEvaluationApplicationService` 或等价应用层入口。
- [ ] 定义 `EvaluationDataset` 或等价概念，能表达 dataset name、version、fixture 列表、默认阈值和 metric 权重。
- [ ] 定义 `GoldenTaskFixture` 或等价概念，能表达 fixture id、能力标签、输入摘要、预期结果和 fixture setup metadata。
- [ ] 定义 `EvaluationRun` 或等价结果对象，能记录 run id、dataset、profile、provider mode、status、overall score、startedAt、completedAt 和 summary。
- [ ] 定义 `EvaluationCaseResult` 或等价结果对象，能记录 fixture id、status、actual summary、expected summary、失败原因和 metric results。
- [ ] 定义 `EvaluationMetricResult` 或等价结果对象，能记录 metric name、score、threshold、weight、passed、actual、expected 和 diagnostic message。
- [ ] 定义 `EvaluationReport` 或等价结构化报告，聚合 run summary、case summary、metric summary 和 recommended fixes。
- [ ] 提供一个最小 smoke dataset / fixture，能通过 `AgentEvaluationApplicationService` 跑出成功结果。
- [ ] smoke fixture 默认使用 deterministic fake provider，不调用真实 LLM、真实 embedding 或真实外部 HTTP。
- [ ] evaluation run 支持 dataset-level pass/fail 状态，至少能表达 passed、failed、partial、error 或等价状态。
- [ ] evaluation report 同时包含机器可断言结构和人类可读摘要。
- [ ] evaluation 运行不会污染正常任务数据；fixture 数据有隔离 namespace、唯一 run id 或等价隔离机制。
- [ ] repeated run 不会因为残留 fixture 数据或重复 id 失败。
- [ ] 测试覆盖 smoke dataset 加载、evaluation run 创建、case result 生成、metric result 生成、report 汇总和默认 fake provider mode。

## Blocked by

None - can start immediately.
