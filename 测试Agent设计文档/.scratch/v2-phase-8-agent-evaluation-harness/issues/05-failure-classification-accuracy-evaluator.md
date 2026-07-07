状态：ready-for-agent

# Issue 05：Failure classification accuracy evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Failure classification accuracy evaluator。系统需要用固定执行事实评估 FailureAnalysis 的失败归因、风险等级、下一步建议和 memory candidate 生成是否符合 golden fixture。

这个 issue 要证明 ProbeFlow 不只是能记录失败，还能稳定判断失败类型，并把高价值失败交给后续记忆闭环。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected failure reason。
- [ ] golden fixture 可以声明 expected risk level。
- [ ] golden fixture 可以声明 expected next suggestion。
- [ ] golden fixture 可以声明 expected memory candidate presence。
- [ ] evaluator 能使用固定 ExecutionRecord / Observation / StepOutcome 或等价执行事实驱动 FailureAnalysis。
- [ ] evaluator 能区分鉴权失败、参数校验失败、环境缺失、服务异常或等价失败类型。
- [ ] evaluator 能检查风险等级是否被低估或高估。
- [ ] evaluator 能检查 next suggestion 是否指向合理下一步，例如补环境、调整用例、分析服务错误、进入人工确认。
- [ ] evaluator 能检查高价值失败是否产生 memory candidate 或 candidate handoff。
- [ ] evaluator 不断言失败分析内部私有 helper 或文本拼接顺序。
- [ ] metric result 能输出 reason mismatch、risk mismatch、suggestion mismatch、missing candidate 等诊断。
- [ ] 测试覆盖鉴权失败、参数校验失败、环境缺失、服务异常和高价值 memory candidate。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
