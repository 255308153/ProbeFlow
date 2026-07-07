状态：ready-for-agent

# Issue 05：Local run command, output directory and usage notes

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

提供同事可以直接运行的本地入口，把前面完成的 harness、订单 fixture、staged sections、安全边界和报告输出串成一个稳定命令。运行完成后，命令或测试输出需要清楚告诉开发者 JSON/Markdown report 的位置。

这个 issue 的目标是把 V3-1 从“应用层测试能跑”提升为“拉取项目后能手动演示”。入口可以是 Maven profile、Spring Boot runner、dedicated integration test 或等价方式，但业务逻辑必须继续复用 harness 应用层入口。

## Acceptance criteria

- [ ] 提供一个稳定的本地运行入口，能运行默认 V3-1 demo fixture。
- [ ] 本地运行入口默认使用 deterministic fake provider。
- [ ] 本地运行入口默认不访问真实外部 HTTP。
- [ ] 本地运行入口支持指定 fixture id。
- [ ] 本地运行入口支持指定输出目录，或使用清晰的默认输出目录。
- [ ] 运行完成后输出 JSON report 路径。
- [ ] 运行完成后输出 Markdown report 路径。
- [ ] 输出目录不会污染源码主路径中的业务代码或迁移目录。
- [ ] 文档或测试命名能让同事知道如何运行 V3-1 harness。
- [ ] 文档说明默认 fake 模式、manual real LLM 显式开启要求、输出产物和常见失败诊断。
- [ ] 连续运行两次不会因为输出目录、run id 或 fixture 数据冲突失败。
- [ ] 测试覆盖本地入口调用 harness、输出目录创建、artifact path 返回和默认 fake/no external HTTP 边界。

## Blocked by

- Issue 01：Harness contract, fixture registry and smoke run foundation
- Issue 02：Order suite fixture and fake HTTP execution path
- Issue 03：Staged V3 sections and phase integration slots
- Issue 04：Provider, external HTTP safety, diagnostics and redaction
