Status: ready-for-agent

# V5-2 Issue 04：Identity Conflict Guard 与冲突审计

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

为 Memory Fact dedup / merge 增加身份冲突保护，避免看起来相似但属于不同系统、模块、接口、错误码或业务实体的事实被误合并。

完成后，系统在合并候选事实前必须检查关键 identity hints。若 system、module、apiPath、httpMethod、errorCode、business entity、failure classification 等关键身份冲突，候选不能覆盖或合并到已有 Long-term Memory。系统应返回清晰 conflict / rejected decision，并在 audit summary 或 metadata 中留下可解释信息。

这个 issue 的目标是让 Memory Dedup 更像“事实身份判断”，而不是危险的模糊文本合并。

## Acceptance criteria

- [ ] dedup / merge 前检查 systemName 或等价系统身份冲突。
- [ ] dedup / merge 前检查 module 或等价模块身份冲突。
- [ ] dedup / merge 前检查 apiPath 和 httpMethod 冲突。
- [ ] dedup / merge 前检查 errorCode 冲突。
- [ ] dedup / merge 前检查 business entity 或等价业务对象冲突。
- [ ] dedup / merge 前检查 failure classification 或等价失败类型冲突。
- [ ] 关键身份冲突时，不会合并到已有 Long-term Memory，也不会覆盖已有 summary/content/evidence。
- [ ] 冲突候选返回 `identity-conflict`、`conflict-candidate` 或等价外部可见原因。
- [ ] 冲突审计信息包含冲突字段、已有值、候选值或等价可解释摘要。
- [ ] 非冲突且同事实的候选仍能按 Issue 03 的规则合并。
- [ ] 有测试覆盖 system、module、apiPath、errorCode、business entity、failure classification 冲突和一个非冲突合并对照样本。

## Blocked by

- `02-memory-fact-quality-gate-pollution-guard.md`
- `03-fact-fingerprint-dedup-evidence-merge.md`
