Status: ready-for-agent

# V5-2 Issue 08：V5-2 Acceptance Boundary Guard

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

为 V5-2 增加验收边界保护，确保本阶段完成的是 Memory Fact Extraction、Quality Gate、Dedup / Merge、Conflict Guard、Evidence Ledger 和来源接入，不会悄悄扩展成后续 V5-3 / V5-4 / V5-5，也不会引入 mem0、VikingDB 或默认真实 LLM 依赖。

该 issue 是 V5-2 的收口保护。它应验证 Memory Refinery 仍是长期记忆写入的唯一核心入口，默认测试稳定，事实提纯链路可解释，边界清晰。

## Acceptance criteria

- [ ] 验收测试确认项目没有直接引入 mem0 SDK 依赖。
- [ ] 验收测试确认项目没有直接引入 VikingDB 依赖。
- [ ] 验收测试确认默认配置和默认 `mvn test` 不调用真实外部 LLM。
- [ ] 验收测试确认 Memory Refinery 是长期记忆写入的统一核心入口，关键来源不能绕过事实提纯直接写 Long-term Memory。
- [ ] 验收测试确认 Memory Candidate 到 Long-term Memory 的路径包含 fact extraction 或等价事实提纯结果。
- [ ] 验收测试确认低质量候选不会写入 Long-term Memory。
- [ ] 验收测试确认重复事实会合并或保持幂等，不会生成重复长期记忆。
- [ ] 验收测试确认 identity conflict 不会被错误合并。
- [ ] 验收测试确认 evidence ledger metadata 或等价审计信息存在。
- [ ] 验收测试确认 V5-2 没有实现 V5-3 Memory Entity Graph。
- [ ] 验收测试确认 V5-2 没有实现 V5-4 Query Rewrite、多路召回或 RRF。
- [ ] 验收测试确认 V5-2 没有实现 V5-5 Cross-Encoder rerank、LLM rerank 或 Small-to-Big。
- [ ] 验收测试确认 V5-1 embedding profile metadata 在 Memory 写入和合并后仍保留。
- [ ] 完整 `mvn test` 在无真实 LLM、无 mem0、无 VikingDB、无外部 embedding 服务的环境下通过。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
- `02-memory-fact-quality-gate-pollution-guard.md`
- `03-fact-fingerprint-dedup-evidence-merge.md`
- `04-identity-conflict-guard-audit.md`
- `05-agent-memory-feedback-sources-fact-pipeline.md`
- `06-llm-assisted-fact-extractor-optional-contract.md`
- `07-v5-2-docs-memory-fact-pipeline.md`
