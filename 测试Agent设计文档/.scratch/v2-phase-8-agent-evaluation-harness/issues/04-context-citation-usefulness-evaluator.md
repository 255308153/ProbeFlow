状态：ready-for-agent

# Issue 04：Context citation usefulness evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Context citation usefulness evaluator。系统需要用 golden fixture 评估 UnifiedContextBuilder 输出的知识引用、长期记忆引用、接口结构覆盖、token budget、low confidence 标记和无关上下文惩罚。

这个 issue 要证明 RAG 和 Memory 不是简单堆上下文，而是能被固定样例验证“召回了该召回的，少召回无关的，并且带上可审计引用”。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected knowledge citations。
- [ ] golden fixture 可以声明 expected memory citations。
- [ ] golden fixture 可以声明 expected API context coverage。
- [ ] golden fixture 可以声明 allowed irrelevant citation count 或 penalty threshold。
- [ ] evaluator 通过 UnifiedContextBuilder 或等价最高上下文接缝获取 ContextBundle。
- [ ] evaluator 能检查 expected knowledge chunk 或 document citation 是否出现。
- [ ] evaluator 能检查 expected long-term memory citation 是否出现。
- [ ] evaluator 能检查 context coverage 中 api、knowledge、memory、task state 等关键维度。
- [ ] evaluator 能检查 token budget 是否被遵守。
- [ ] evaluator 能检查 low confidence / stale / archived memory 是否被正确标记或排除。
- [ ] evaluator 对无关 citation 产生惩罚，而不是只要有引用就得满分。
- [ ] metric result 能输出 missing citation、irrelevant citation、budget overflow、coverage gap 等诊断。
- [ ] 测试覆盖 expected knowledge citation、expected memory citation、irrelevant citation penalty、token budget 和 low confidence 标记。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
