Status: ready-for-agent

# V5-1 Issue 03：Knowledge / Memory 向量写入的 Profile 元数据与 Reindex Guard

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v5-phase-1-real-embedding-pgvector-retrieval/PRD.md`

## What to build

让 Knowledge Chunk 和 Long-term Memory 的向量写入记录 embedding profile 元数据，并在 provider profile 变化时能识别旧向量与当前检索配置不一致。

这个 slice 要覆盖写入端：Knowledge Ingest 写入文档 chunk 时记录向量来源，Memory Refinery 写入或合并长期记忆时记录向量来源。后续 pgvector 召回可以基于这些元数据判断当前向量是否可用、是否需要 reindex，避免不同模型、不同维度或不同 prefix 策略生成的向量被静默混用。

## Acceptance criteria

- [ ] Knowledge Chunk 写入时记录 embedding profile id、provider mode、model、dimension 或等价元数据。
- [ ] Long-term Memory 写入时记录 embedding profile id、provider mode、model、dimension 或等价元数据。
- [ ] Memory 合并后会基于合并后的 summary/content 重新生成 embedding，并更新 profile 元数据。
- [ ] 文档 revision 更新后，新 active latest chunks 使用当前 embedding profile，旧 chunks 不应继续作为 active latest 结果被召回。
- [ ] 写入时发现 provider 返回维度与配置或 schema 不一致，会清晰失败。
- [ ] 检索前或检索结果中能识别 embedding profile mismatch / reindex required。
- [ ] profile mismatch 不会被静默当作正常真实语义召回。
- [ ] 有测试覆盖 Knowledge 写入、Memory 写入、Memory 合并、profile mismatch 标记和维度错误。

## Blocked by

- `01-embedding-profile-contract-and-fake-baseline.md`
