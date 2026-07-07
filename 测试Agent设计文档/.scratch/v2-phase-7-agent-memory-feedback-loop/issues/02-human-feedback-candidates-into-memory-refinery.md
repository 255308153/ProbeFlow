状态：ready-for-agent

# Issue 02：Human feedback candidates into MemoryRefinery

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

把 Phase 6 的 HumanDecision memory candidate handoff 接入 MemoryRefinery，让人类采纳、拒绝、修改建议、blocker resolution、planner clarification 和 high-risk rejection 不再停留在候选摘要，而是进入长期记忆治理流程。

完成后，人类反馈可以被接受、拒绝、合并或标记重复，并且所有结果都能被审计。

## Acceptance criteria

- [ ] AgentMemoryFeedbackApplicationService 可以根据 HumanDecisionRecord 或 decision id 生成并提交候选记忆。
- [ ] 复用 Phase 6 human feedback candidate handoff，不重复实现一套人类反馈摘要逻辑。
- [ ] PROMOTE_DRAFT 反馈可以进入 testing pattern 或 preference 类型长期记忆。
- [ ] DISCARD_DRAFT 反馈可以进入 preference 或 testing pattern 类型长期记忆。
- [ ] REQUEST_CHANGES 反馈可以进入用例生成偏好或改进建议记忆。
- [ ] RESOLVE_BLOCKER / PROVIDE_INPUT 反馈可以进入 project knowledge 或 blocker resolution 经验。
- [ ] planner clarification answer 可以进入 preference、project knowledge 或 testing pattern 记忆。
- [ ] high-risk rejection 可以进入 policy learning note 或等价安全边界记忆。
- [ ] human-approved feedback 默认拥有比普通系统候选更高的初始置信度。
- [ ] 不符合 eligibility 的人类反馈会被拒绝，并记录 rejection reason。
- [ ] 敏感输入不会明文进入 candidate、LongTermMemory content 或 metadata。
- [ ] 同一个 decision 重复提交不会重复创建长期记忆。
- [ ] 测试覆盖 promote、discard、request changes、blocker resolution、clarification、high-risk rejection、重复提交和脱敏。

## Blocked by

- Issue 01：Agent memory feedback contract and candidate audit foundation
