状态：ready-for-agent

# Issue 07：Usefulness feedback scoring loop

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

实现 memory usefulness feedback：当某条长期记忆被后续任务引用后，系统可以记录它对任务结果是 positive、negative、neutral 还是 unknown，并据此调整 confidence、importance、successContribution 或 status。

完成后，记忆评分不再是静态字段，而会根据后续使用效果反向校准。

## Acceptance criteria

- [ ] 可以针对 memory usage record 提交 usefulness feedback。
- [ ] feedback 至少支持 positive、negative、neutral、unknown 或等价结果。
- [ ] positive feedback 可以适度提升 successContribution、confidence 或 importance。
- [ ] negative feedback 可以降低 successContribution、confidence 或 importance。
- [ ] neutral / unknown feedback 不会剧烈改变评分。
- [ ] repeated negative feedback 可以把 memory 降为 inactive 或等价谨慎状态。
- [ ] archived memory 不接受普通 usefulness feedback 或有明确拒绝行为。
- [ ] 相同 usage / actor / outcome 的重复 feedback 幂等，不重复升降权。
- [ ] feedback audit 记录 actor、reason、task id、usage id、score delta 和 sanitized summary。
- [ ] score 更新有上下限保护。
- [ ] 评分更新和 feedback record 在事务边界内完成。
- [ ] 测试覆盖 positive、negative、neutral、duplicate feedback、score bounds、status transition 和 audit。

## Blocked by

- Issue 05：Memory dedup, merge and confidence reinforcement
- Issue 06：Memory usage record from context recall
