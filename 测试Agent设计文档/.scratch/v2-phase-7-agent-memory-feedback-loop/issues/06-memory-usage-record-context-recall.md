状态：ready-for-agent

# Issue 06：Memory usage record from context recall

## Parent

V2 Phase 7：Agent Memory Feedback Loop PRD

## What to build

记录长期记忆在 UnifiedContextBuilder 中被召回、进入 citations、并交给 Planner、TestCaseGeneration 或 FailureAnalysis 使用的事实。系统需要知道“哪条记忆被哪次任务用过”，后续 usefulness feedback 才能调整评分。

完成后，长期记忆不只是被检索出来，还能形成可审计的 usage record。

## Acceptance criteria

- [ ] UnifiedContextBuilder 或等价上下文构建路径可以暴露 long-term memory citations。
- [ ] 可以为被召回的 LongTermMemory 记录 usage record。
- [ ] usage record 包含 memory id、task id、stage profile、consumer、source ref、score、match reasons、created timestamp。
- [ ] usage record 能关联 ContextCitation 或等价引用信息。
- [ ] Planner、TestCaseGeneration、FailureAnalysis 等 consumer 可以被区分。
- [ ] 重复构建同一 task / stage / memory 的 context 有明确幂等或多次使用语义。
- [ ] archived / inactive memory 不会产生新的 usage record。
- [ ] token budget、stage profile、api/module/error filters 仍然限制长期记忆召回。
- [ ] low-confidence recalled memory 会在 usage record 或 citation 中保留标记。
- [ ] 本 issue 不根据 usage record 调整评分；评分反馈留给后续 issue。
- [ ] 测试覆盖 context recall、usage record、citations、consumer 区分、过滤规则和 archived/inactive memory。

## Blocked by

- Issue 01：Agent memory feedback contract and candidate audit foundation
