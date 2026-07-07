状态：ready-for-agent

# Issue 03：Execution and StepOutcome candidate intake

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

把 StepOutcome、ExecutionRecord 和 FailureAnalysis 的高价值失败经验接入统一的 Agent Memory Feedback Loop。现有 FailureAnalysis 已经能生成长期记忆，本 issue 要把它纳入统一 candidate audit / result 语义，同时保留低价值噪音拒绝规则。

完成后，失败经验不只是局部分析结果，而是可审计、可合并、可召回的 Agent 学习材料。

## Acceptance criteria

- [ ] 可以从失败 StepOutcome 生成 memory candidate intake。
- [ ] 可以从失败 ExecutionRecord 生成 memory candidate intake。
- [ ] 可以复用 FailureAnalysis 的失败分类和 MemoryRefinery 路径。
- [ ] 严重失败、重复失败或高风险失败可以被接受为长期记忆候选。
- [ ] 低置信度候选被拒绝，并记录 rejection reason。
- [ ] 单次低风险偶发失败被拒绝或保持低优先级，不污染长期记忆。
- [ ] noisy passed execution 不生成长期记忆。
- [ ] 候选 metadata 包含 classification、risk level、retryable、execution id、case id、api path、status code、error code 等可用信息。
- [ ] 原始 Observation、ExecutionRecord 或 StepOutcome 不会被删除或覆盖。
- [ ] 重复分析同一个 execution / step 不会重复写入长期记忆。
- [ ] 现有 FailureAnalysis 测试继续通过，不破坏已有报告和任务记忆行为。
- [ ] 测试覆盖 StepOutcome、ExecutionRecord、FailureAnalysis、低价值拒绝、重复提交和原始记录保留。

## Blocked by

- Issue 01：Agent memory feedback contract and candidate audit foundation
