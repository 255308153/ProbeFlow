Status: ready-for-agent

# V5-2 Issue 02：Memory Fact Quality Gate 与污染防护

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

为 Memory Fact 写入长期记忆前增加质量门禁，防止长期记忆被低质量、一次性、任务局部、无证据、过泛或敏感内容污染。

完成后，Memory Refinery 对每个候选事实都必须给出结构化 quality decision。可复用事实可以继续写入 Long-term Memory；不可复用事实必须被拒绝，并返回清晰 rejection reason。该门禁需要在 deterministic 默认路径下工作，不能依赖真实 LLM。

这个 issue 要保证 Memory 不再只是“只要 confidence 够就写入”，而是明确判断“这是否是一条值得跨任务复用的长期事实”。

## Acceptance criteria

- [ ] Quality Gate 能对 Memory Fact 返回 accepted / rejected 或等价结构化 decision。
- [ ] 空候选被拒绝，并返回 `empty-candidate` 或等价原因。
- [ ] 低置信度候选被拒绝，并返回 `low-confidence` 或等价原因。
- [ ] one-off、temporary、single run、scratch note 等一次性噪音被拒绝。
- [ ] task-local only、for this task only、do not reuse 等任务局部信息被拒绝。
- [ ] 没有可复用事实的候选被拒绝，并返回 `no-reusable-fact` 或等价原因。
- [ ] 缺少证据来源或证据摘要的候选被拒绝，并返回 `missing-evidence` 或等价原因。
- [ ] 太泛、无法指导后续任务的候选被拒绝，并返回 `too-generic` 或等价原因。
- [ ] sanitization 后仍包含 token、password、cookie、authorization 等敏感内容的候选不会写入长期记忆。
- [ ] 被拒绝的候选不会写 Long-term Memory，也不会写 embedding。
- [ ] Agent Memory Feedback 的 audit / refinery result summary 能看见质量门禁拒绝原因。
- [ ] 有测试覆盖每类主要拒绝原因和一个正常 accepted 对照样本。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
