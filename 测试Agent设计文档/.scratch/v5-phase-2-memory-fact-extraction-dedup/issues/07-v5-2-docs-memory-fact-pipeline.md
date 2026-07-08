Status: ready-for-agent

# V5-2 Issue 07：V5-2 文档与 Memory Fact Pipeline 说明

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-2-memory-fact-extraction-dedup/PRD.md`

## What to build

补齐 V5-2 的中文文档，让用户和同事能理解 ProbeFlow 的自研 Memory Fact pipeline，以及它和 mem0 思想、Viking 统一上下文思想、V5-1 embedding / pgvector 地基之间的关系。

文档需要服务协作开发和面试讲解：用户要能说明 ProbeFlow 为什么不是简单保存日志，为什么不直接依赖 mem0 / VikingDB，为什么长期记忆要经过事实抽取、质量门禁、去重合并、冲突检测和证据账本，以及后续 V5-3 / V5-4 / V5-5 会继续补什么。

## Acceptance criteria

- [ ] 文档说明 V5-2 Memory 写路径：Candidate -> Sanitization -> Fact Extraction -> Classification -> Quality Gate -> Dedup / Merge / Conflict Check -> Evidence Ledger -> Long-term Memory -> Embedding -> Unified Context。
- [ ] 文档说明 ProbeFlow 借鉴 mem0 的记忆提纯思想，但不引入 mem0 SDK 或运行时依赖。
- [ ] 文档说明 ProbeFlow 借鉴 Viking 的统一上下文思想，但不引入 VikingDB 依赖。
- [ ] 文档说明 Memory Fact 的核心字段和主要 fact type。
- [ ] 文档说明 Quality Gate 如何防止 memory pollution。
- [ ] 文档说明 fact fingerprint、evidenceCount、mergeCount、mergedSourceRefs、confidence / importance 增强的意义。
- [ ] 文档说明 identity conflict guard 为什么重要，并举例说明 system、module、apiPath、errorCode 冲突不能合并。
- [ ] 文档说明 deterministic extractor 与 LLM-assisted extractor 的区别，以及默认测试不调用真实 LLM。
- [ ] 文档说明 V5-2 与 V5-1 embedding / pgvector 的关系。
- [ ] 文档明确 V5-2 不做 V5-3 Entity Graph、V5-4 Query Rewrite / Multi-route Retrieval、V5-5 Rerank / Small-to-Big。
- [ ] 文档给出面试讲解版总结，能让用户解释“Agent 如何从执行经验中学习”。

## Blocked by

- `01-memory-fact-contract-deterministic-refinery-baseline.md`
- `02-memory-fact-quality-gate-pollution-guard.md`
- `03-fact-fingerprint-dedup-evidence-merge.md`
- `04-identity-conflict-guard-audit.md`
- `05-agent-memory-feedback-sources-fact-pipeline.md`
- `06-llm-assisted-fact-extractor-optional-contract.md`
