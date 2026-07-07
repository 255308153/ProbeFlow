状态：ready-for-agent

# Issue 08：Next-task recall learning loop acceptance path

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

实现并测试一条完整的 Agent Memory Feedback Loop 验收路径：上一轮任务产生经验，经验进入长期记忆，下一轮任务通过 UnifiedContextBuilder 召回这条经验，后续结果再对该记忆提交 usefulness feedback。

这个 issue 是 Phase 7 的闭环证明，重点不是新增很多新模型，而是把候选、refinery、recall、citation、usage、feedback 串起来。

## Acceptance criteria

- [ ] 第一轮任务的人类反馈、失败分析或 policy rejection 可以进入长期记忆。
- [ ] 第二轮相似任务可以通过 UnifiedContextBuilder 召回上一轮长期记忆。
- [ ] ContextBundle citations 能证明该长期记忆被放入上下文。
- [ ] Planner、TestCaseGeneration 或 FailureAnalysis 的测试路径能消费包含长期记忆的 context。
- [ ] 被召回的 memory 会产生 usage record。
- [ ] 后续任务成功或人工采纳可以生成 positive usefulness feedback。
- [ ] 后续任务失败或人工指出误导可以生成 negative usefulness feedback。
- [ ] feedback 后 memory 的 successContribution / confidence / importance 发生合理变化。
- [ ] 所有流程使用 fake provider 或 deterministic fallback，不依赖真实 LLM。
- [ ] 不让 LLM 直接写 LongTermMemory。
- [ ] 不删除原始 Observation、ExecutionRecord、HumanDecisionRecord 或 PolicyValidationResult。
- [ ] 测试覆盖至少一条 end-to-end closed loop，并验证候选、memory、citation、usage、feedback 和 scoring。

## Blocked by

- Issue 02：Human feedback candidates into MemoryRefinery
- Issue 03：Execution and StepOutcome candidate intake
- Issue 04：Policy and planner learning notes
- Issue 06：Memory usage record from context recall
- Issue 07：Usefulness feedback scoring loop
