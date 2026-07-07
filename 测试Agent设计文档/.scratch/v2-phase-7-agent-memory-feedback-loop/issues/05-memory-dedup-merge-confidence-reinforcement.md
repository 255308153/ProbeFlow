状态：ready-for-agent

# Issue 05：Memory dedup, merge and confidence reinforcement

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

强化长期记忆的去重、合并和评分更新规则，让来自人类反馈、执行失败和策略拦截的相似候选形成更强的一条经验，而不是堆积为多条散乱记忆。

完成后，重复证据会提升可信度和重要性，合并结果保留来源链路，并且评分不会无限膨胀。

## Acceptance criteria

- [ ] 相同 source type / source ref 的候选不会重复创建长期记忆。
- [ ] 相似 summary / content / tags / metadata 的候选可以合并到已有 active memory。
- [ ] 合并后的 memory 记录 merged source refs。
- [ ] 合并后的 memory 记录 merge count 或等价出现次数。
- [ ] 合并后的 memory 保留更完整的 evidence，不丢失原来源摘要。
- [ ] confidence 会根据重复证据、人类确认、风险等级合理提升。
- [ ] confidence 有上限保护，不会因为重复反馈无限增长。
- [ ] importance 会根据风险、影响范围、重复次数和人类反馈调整。
- [ ] successContribution 不只由创建时决定，允许后续 feedback 更新。
- [ ] archived / inactive memory 不应被错误合并，除非明确有恢复规则。
- [ ] 合并过程更新 embedding 或等价检索表示。
- [ ] 测试覆盖 exact duplicate、semantic merge、human feedback merge、failure pattern merge、policy learning merge、score bounds 和 archived/inactive 边界。

## Blocked by

- Issue 02：Human feedback candidates into MemoryRefinery
- Issue 03：Execution and StepOutcome candidate intake
- Issue 04：Policy and planner learning notes
