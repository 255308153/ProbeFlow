Status: ready-for-agent

# V5-2 Issue 01：Memory Fact 契约与 Deterministic Refinery 基线

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

建立 V5-2 的 Memory Fact 契约，并把 Memory Refinery 的最小写路径从“候选文本压缩”升级为“候选记忆 -> 结构化事实 -> 长期记忆”的可验证基线。

完成后，一个高质量 Memory Candidate 进入系统时，Memory Refinery 应先抽取 deterministic / fake-friendly 的 Memory Fact，再根据 fact type、summary、content、applicability、trigger、tags、identity hints、evidence entries、confidence、importance、reuse score 等信息写入 Long-term Memory。写入后的长期记忆必须继续保留 V5-1 的 embedding profile metadata，并能被现有 Memory 检索路径消费。

这个 issue 不追求完整门禁、复杂合并和 LLM 抽取。它只打通一条最小但完整的事实提纯主线，为后续质量门禁、dedup、冲突检测和来源接入提供稳定基线。

## Acceptance criteria

- [ ] 存在明确的 Memory Fact 领域概念，能表达 fact type、summary、content、applicability、trigger、tags、identity hints、evidence entries、confidence、importance、reuse score 和 quality status。
- [ ] Memory Refinery 对可复用候选执行 deterministic fact extraction，而不是直接把原始候选压缩为长期记忆。
- [ ] failure pattern、testing pattern、project knowledge、preference 至少能通过 deterministic 规则被识别为不同 fact type。
- [ ] 接受的 Memory Fact 能写入 Long-term Memory，并在 metadata 中保留 fact type、fact fingerprint 或等价标识、identity hints 和 evidence summary。
- [ ] 写入 Long-term Memory 时继续通过 V5-1 EmbeddingService 写入 embedding，并保留 embedding profile metadata。
- [ ] MemoryRefineryResult 或等价结果能表达 accepted / created / duplicate / rejection reason / refined memory view 等外部可见状态。
- [ ] 已有 Memory Refinery 行为没有被破坏，低风险候选仍能产出长期记忆。
- [ ] 有应用层或服务层测试覆盖 Memory Candidate -> Memory Fact -> Long-term Memory -> embedding metadata 的完整路径。
- [ ] 默认测试不依赖真实 LLM、mem0 或 VikingDB。

## Blocked by

None - can start immediately
