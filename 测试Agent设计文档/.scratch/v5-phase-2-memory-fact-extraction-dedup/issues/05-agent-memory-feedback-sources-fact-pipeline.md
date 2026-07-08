Status: ready-for-agent

# V5-2 Issue 05：Agent Memory Feedback 来源接入事实管道

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

让真实 Agent Memory Feedback 来源全部进入 V5-2 fact-aware Memory Refinery，而不是只覆盖手工构造的 Memory Candidate。

完成后，失败分析、StepOutcome、Human Decision、Policy Learning Note、Suite Failure Analysis 等候选都应通过应用层入口完成 intake、sanitization、idempotency、candidate record、audit summary，再进入 Memory Refinery 的事实抽取、质量门禁、dedup / merge 和 evidence ledger 路径。

这个 issue 的重点是打通真实业务来源，让 V5-2 不是孤立服务测试，而是 Agent 闭环中的 Memory 写路径。

## Acceptance criteria

- [ ] 失败分析候选通过 Agent Memory Feedback 入口进入 fact-aware Memory Refinery，并能生成 failure pattern 事实。
- [ ] StepOutcome 候选通过 Agent Memory Feedback 入口进入 fact-aware Memory Refinery，并保留 stepId、stepStatus、risk/retry 等来源信息。
- [ ] Human Decision 候选通过 Agent Memory Feedback 入口进入 fact-aware Memory Refinery，并体现人工确认或人工修订对 confidence / importance 的影响。
- [ ] Policy Learning Note 候选通过 Agent Memory Feedback 入口进入 fact-aware Memory Refinery，并保留 policy reason、tool name、risk level 等策略信息。
- [ ] Suite Failure Analysis 候选通过 Agent Memory Feedback 入口进入 fact-aware Memory Refinery，并保留 executionId、suiteId、caseId、rootStepId、affectedDownstreamStepIds、failureClassification 等证据字段。
- [ ] 所有来源都继续经过 sanitization，不允许绕过 MemoryFeedbackSanitizer 或等价安全处理。
- [ ] 所有来源都继续生成 candidate record，并保留 pending / accepted / duplicate / rejected 或等价处理状态。
- [ ] 所有来源的 refinery result summary 能体现 fact extraction、quality decision、dedup / merge 或 rejection reason。
- [ ] 终态 Task 或无效 Task 的候选仍按现有入口规则被拒绝，不因 V5-2 放宽。
- [ ] 有应用层测试覆盖至少 failure analysis、StepOutcome、Human Decision、Policy Learning Note、Suite Failure Analysis 五类来源。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
- `02-memory-fact-quality-gate-pollution-guard.md`
