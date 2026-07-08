Status: ready-for-agent

# V5-2 Issue 03：Fact Fingerprint Dedup 与 Evidence Merge

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

把 Memory 去重从“文本相似或同源判断”升级为“事实身份 + 证据增强”的 dedup / merge 路径。

完成后，同一事实从不同来源重复出现时，系统不应创建多条长期记忆，而应把它们合并到同一条 Long-term Memory 中，累加 evidenceCount、mergeCount、mergedSourceRefs、mergedSourceTypes、evidenceSummaries，并增强 confidence、importance、successContribution。合并后的长期记忆必须基于最新 summary/content/evidence 重新写 embedding。

这个 issue 需要让 Memory 越用越干净、越用越可信，而不是随着执行次数增加变成重复经验堆。

## Acceptance criteria

- [ ] Memory Fact 有稳定 fingerprint 或等价 dedup key，综合 fact type、normalized summary/content、identity hints、source hints 和 evidence hints。
- [ ] 同一 sourceType + sourceRef + taskId 重复提交时保持幂等，不重复写 Long-term Memory。
- [ ] 不同 sourceRef 但相同 fact fingerprint 的候选会合并到已有长期记忆。
- [ ] 文本或语义相似但不是 exact fingerprint 的候选，只有 identity hints 不冲突时才允许作为相似事实合并。
- [ ] 合并后 metadata 更新 evidenceCount、mergeCount、mergedSourceRefs、mergedSourceTypes、evidenceSummaries 和 lastMergedAt。
- [ ] 合并后 confidence、importance、successContribution 可增强，但必须有上限，避免无限膨胀。
- [ ] 人工确认、重复证据、高风险故障或明确业务身份能合理提升 confidence 或 importance。
- [ ] 合并后 Long-term Memory 基于更新后的内容重新生成 embedding。
- [ ] 合并后仍保留 V5-1 embedding profile metadata。
- [ ] duplicate / merged 状态能通过 MemoryRefineryResult 或等价结果被外部观察。
- [ ] 有测试覆盖同源幂等、同事实合并、相似事实合并、评分增强、metadata evidence ledger 更新和重新 embedding。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
