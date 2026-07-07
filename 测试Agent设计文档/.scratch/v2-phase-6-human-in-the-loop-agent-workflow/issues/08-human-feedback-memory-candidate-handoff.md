状态：ready-for-agent

# Issue 08：Human feedback memory candidate handoff

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

把人类决策和反馈整理成 MemoryCandidate 输入，为 Phase 7 的 Memory Feedback Loop 做准备。系统应能从草稿采纳/丢弃、blocker 解决、高风险拒绝、Planner 澄清等场景生成可审计的候选记忆，但不能在本阶段直接完成长期记忆学习闭环。

完成后，面试中可以讲清楚：人类反馈不是简单日志，而是未来 Agent 改进的结构化材料。

## Acceptance criteria

- [ ] promote draft feedback 可以生成 memory candidate request 或等价候选摘要。
- [ ] discard draft feedback 可以生成 memory candidate request 或等价候选摘要。
- [ ] request changes feedback 可以生成 memory candidate request 或等价候选摘要。
- [ ] blocker resolution feedback 可以生成 memory candidate request 或等价候选摘要。
- [ ] high-risk rejection feedback 可以生成 memory candidate request 或等价候选摘要。
- [ ] planner clarification answer 可以生成 memory candidate request 或等价候选摘要。
- [ ] memory candidate 包含来源 request、decision、task、actor、reason 和 sanitized summary。
- [ ] 敏感字段不会进入 memory candidate 明文内容。
- [ ] 系统可以审计哪些 decision 生成了 memory candidate。
- [ ] Phase 6 不自动写长期记忆、不做记忆合并、不更新置信度。
- [ ] 测试覆盖各类反馈候选、敏感信息脱敏、审计关联和不直接写长期记忆边界。

## Blocked by

- Issue 04：Draft review workflow and ManualReviewGate compatibility
- Issue 05：Blocker resolution and missing input workflow
- Issue 06：High-risk approval and rejection workflow
- Issue 07：Planner clarification feedback workflow
