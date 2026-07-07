状态：ready-for-agent

# Issue 04：Policy and planner learning notes

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

从 PolicyValidator 拒绝和 Planner 决策复盘中生成 policy learning note。系统应记录 Planner 提出了什么、为什么被策略拒绝、风险等级是什么、下次应该如何避免类似建议。

完成后，Planner 不只是被 PolicyValidator 当场拦住，还能把这类安全边界变成未来规划上下文中的经验。

## Acceptance criteria

- [ ] PolicyValidationResult 或等价安全拦截结果可以生成 memory candidate。
- [ ] PlannerDecision 的 action、tool、risk level、confidence、reasoning、proposed step 等摘要可以进入候选证据。
- [ ] 被拒绝的高风险或越界建议可以生成 policy learning note。
- [ ] policy learning note 记录 policy reason、blocked action、source trigger 和 recommended safer alternative。
- [ ] 低价值或重复的 policy rejection 被标记 duplicate / rejected，而不是堆积多条记忆。
- [ ] policy learning note 可通过 stage profile、risk、tool name、api path 或 tags 召回。
- [ ] 该路径不让 Planner 或 LLM 直接写 LongTermMemory，仍经过 AgentMemoryFeedback / MemoryRefinery。
- [ ] 敏感 planner input、tool input 或 auth 信息会被脱敏。
- [ ] 测试覆盖 policy rejection candidate、planner decision candidate、重复拒绝合并/去重、脱敏和后续可召回 metadata。

## Blocked by

- Issue 01：Agent memory feedback contract and candidate audit foundation
