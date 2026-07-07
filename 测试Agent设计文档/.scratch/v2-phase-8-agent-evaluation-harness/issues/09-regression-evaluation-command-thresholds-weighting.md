状态：ready-for-agent

# Issue 09：Regression evaluation command, thresholds and weighting

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

把前面各专项 evaluator 汇总成可回归运行的 Agent evaluation suite。系统需要支持 dataset-level score、metric 权重、pass/fail threshold、fixture 隔离和一条稳定 Maven 测试入口，让开发者和同事在提交前可以运行 Agent 能力回归。

这个 issue 的目标是把 Evaluation Harness 从“能单测”提升为“能作为 V2 Agent Core 回归门禁使用”。

## Acceptance criteria

- [ ] dataset 可以声明 case-level threshold。
- [ ] dataset 可以声明 metric-level threshold。
- [ ] dataset 可以声明 metric weight。
- [ ] EvaluationRun 能按权重汇总 overall score。
- [ ] 核心 metric 低于阈值时，EvaluationRun 明确失败。
- [ ] EvaluationReport 能输出 dataset summary、case summary、metric summary、failed metrics 和 recommended fixes。
- [ ] 失败诊断能指向能力维度，例如 planner-decision、policy-validation、context-citation、failure-classification、case-coverage、report-usefulness、memory-reuse。
- [ ] 提供稳定 Maven 测试入口或等价 regression evaluation command。
- [ ] 文档或测试命名能让同事清楚知道如何运行 Phase 8 evaluation suite。
- [ ] regression evaluation 默认只使用 deterministic fake provider。
- [ ] fixture 隔离机制能保证同一 evaluation suite 连续运行两次仍稳定。
- [ ] evaluation report 中 provider mode、dataset version、run profile、startedAt、completedAt 可审计。
- [ ] 测试覆盖 threshold failure、weighted score、report summary、fixture isolation 和 regression command 入口。

## Blocked by

- Issue 02：Planner decision accuracy evaluator
- Issue 03：Tool selection and policy validity evaluator
- Issue 04：Context citation usefulness evaluator
- Issue 05：Failure classification accuracy evaluator
- Issue 06：Test case coverage evaluator
- Issue 07：Report usefulness and no-secret evaluator
- Issue 08：Memory reuse closed-loop evaluator
