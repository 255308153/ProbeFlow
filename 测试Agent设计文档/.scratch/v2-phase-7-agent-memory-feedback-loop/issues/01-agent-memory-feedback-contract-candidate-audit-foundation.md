状态：ready-for-agent

# Issue 01：Agent memory feedback contract and candidate audit foundation

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

建立 Agent Memory Feedback Loop 的最小契约和候选记忆审计基础。系统需要有统一的应用层入口来接收 Agent 运行经验，记录候选来源、状态、证据、脱敏摘要、幂等键和处理结果。

这个 issue 只交付 candidate intake / audit foundation，不把候选真正写入长期记忆，不接入 HumanDecision、ExecutionRecord、PolicyValidator 或 Planner。

## Acceptance criteria

- [ ] 新增 AgentMemoryFeedbackApplicationService 或等价应用层入口。
- [ ] 定义 memory candidate intake request / result 或等价契约。
- [ ] 候选输入包含 source type、source ref、task id、summary、content、raw evidence、tags、confidence、metadata。
- [ ] 候选来源至少能表达 StepOutcome、ExecutionRecord、FailureAnalysis、HumanDecisionRecord、PolicyValidationResult、PlannerDecision、manual/system source。
- [ ] 候选状态至少能表达 pending、accepted、rejected、merged、duplicate、failed 或等价状态。
- [ ] 候选记录能保存 rejection reason、refinery result summary、关联 memory id 和 audit summary。
- [ ] 相同 source type / source ref / task id 的重复提交可以被识别为 duplicate 或等价幂等结果。
- [ ] 候选 raw evidence 不直接进入 audit summary；audit summary 只保存脱敏后的摘要。
- [ ] token、secret、cookie、authorization、password 等敏感字段会被 masking。
- [ ] completed / cancelled task 的候选提交有明确处理规则，不产生半写入。
- [ ] candidate intake、audit record 和 task metadata handoff 在事务边界内完成。
- [ ] 本 issue 不调用 LLM、不调用 MemoryRefinery、不写 LongTermMemory、不修改 UnifiedContextBuilder。
- [ ] 测试覆盖合法 intake、重复 intake、非法输入、敏感字段脱敏、candidate 状态和事务边界。

## Blocked by

None - can start immediately.
