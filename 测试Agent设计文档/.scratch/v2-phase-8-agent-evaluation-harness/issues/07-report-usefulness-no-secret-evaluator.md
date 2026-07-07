状态：ready-for-agent

# Issue 07：Report usefulness and no-secret evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Report usefulness and no-secret evaluator。系统需要评估报告是否包含任务摘要、执行统计、失败证据、建议动作、引用来源、记忆学习摘要，并确保报告和评估诊断中不泄露敏感信息。

这个 issue 要证明报告不是日志拼接，而是有可评估的信息结构和安全边界。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected report sections。
- [ ] golden fixture 可以声明 expected evidence types，例如 execution、observation、citation、memory feedback。
- [ ] evaluator 能消费 ReportGeneration 输出或等价报告结构。
- [ ] evaluator 能检查任务摘要是否存在并表达任务总体结果。
- [ ] evaluator 能检查执行统计是否包含成功、失败、跳过或等价计数。
- [ ] evaluator 能检查失败证据是否能追溯到 execution / observation / citation。
- [ ] evaluator 能检查建议动作是否存在且与失败类型相关。
- [ ] evaluator 能检查引用来源是否进入报告。
- [ ] evaluator 能检查记忆学习摘要或 memory feedback summary 是否进入报告。
- [ ] evaluator 能扫描 report、case diagnostic、metric detail，确认 authorization、cookie、password、secret、token 等敏感字段被脱敏。
- [ ] metric result 能输出 missing section、missing evidence、missing recommendation、secret leakage 等诊断。
- [ ] 测试覆盖报告摘要、执行统计、失败证据、建议动作、引用来源、记忆学习摘要和 no-secret 输出。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
