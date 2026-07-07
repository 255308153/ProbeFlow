状态：ready-for-agent

# Issue 01：Harness contract, fixture registry and smoke run foundation

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

建立 Manual Suite Agent Harness 的最小可运行闭环。系统需要有统一的应用层入口，可以加载一个稳定 fixture，运行一次 deterministic smoke run，并生成包含 run summary、fixture summary、基础 sections、diagnostics 和 artifact references 的结构化结果。

这个 issue 只交付 harness 契约、fixture registry、run request/result/report artifact 基础，以及一个最小 smoke path。不要求接入真实订单链路、不要求完整 SUITE 变量传递，也不实现真实 Business Flow Discovery、DependencyLinker、ExecutionContext 或 Suite Failure Analysis。

## Acceptance criteria

- [ ] 新增 Manual Suite Agent Harness 或等价应用层入口，能通过 request 运行指定 fixture。
- [ ] 定义 harness run request，至少能表达 fixture id、provider mode、输出目录、是否允许手动真实 LLM、run profile 或等价字段。
- [ ] 定义 harness run result，至少能表达 run id、fixture id、fixture version、provider mode、status、startedAt、completedAt、section summaries、diagnostics 和 artifact references。
- [ ] 定义 fixture registry 或等价能力，能按 fixture id 加载稳定 fixture metadata。
- [ ] 提供一个最小 smoke fixture，能在 deterministic fake 模式下成功完成 harness run。
- [ ] smoke run 默认不调用真实 LLM、真实 embedding 或真实外部 HTTP。
- [ ] run result 中包含 `usesRealLlm=false` 和 `usesExternalHttp=false` 或等价安全标记。
- [ ] harness 能生成基础 JSON report artifact。
- [ ] harness 能生成基础 Markdown report artifact。
- [ ] JSON 和 Markdown artifact 都包含相同 run id、fixture id、fixture version、provider mode 和 status。
- [ ] Markdown report 至少包含输入、运行摘要、sections、diagnostics 和 artifact references 这些可读章节。
- [ ] JSON report 至少包含 schemaVersion、run summary、fixture summary、sections、diagnostics 和 artifact metadata。
- [ ] 同一 smoke fixture 连续运行两次不会因为重复 id、残留状态或输出目录冲突失败。
- [ ] 测试覆盖 fixture registry、smoke run、run result、基础 JSON report 和基础 Markdown report。

## Blocked by

None - can start immediately.
