状态：ready-for-agent

# Issue 02：Internal ToolContractRegistry catalog

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

实现 ProbeFlow 内部 ToolContractRegistry，把 V1/V2 已有可规划能力登记成 Agent 可见的工具契约。工具目录应覆盖 API analysis、knowledge retrieval、memory context、test case generation、HTTP execution、failure analysis 和 report generation 等内部能力。

这个 issue 的目标是让后续 Controlled Planner 可以从稳定工具目录中选择能力，而不是靠 prompt 自由猜测系统能做什么。

## Acceptance criteria

- [ ] 实现内部 ToolContractRegistry 或等价工具目录入口。
- [ ] 工具目录可以列出全部已登记工具契约。
- [ ] 工具目录可以按工具名称查询单个工具契约。
- [ ] 登记 API analysis 工具契约，并标明所需 SourceMaterial 或等价输入。
- [ ] 登记 knowledge retrieval 工具契约，并标明它是只读上下文能力。
- [ ] 登记 memory context 工具契约，并标明它是只读上下文能力。
- [ ] 登记 test case generation 工具契约，并标明输出是草稿且受 review gate 控制。
- [ ] 登记 HTTP execution 工具契约，并标明它是高风险能力。
- [ ] 登记 failure analysis 工具契约，并标明它依赖 execution result 或 failure signal。
- [ ] 登记 report generation 工具契约，并标明它依赖任务过程数据。
- [ ] 工具目录不登记外部工具、MCP 工具、插件市场工具、ticket 工具或真实 CI 操作工具。
- [ ] 测试覆盖工具存在性、契约字段完整性、按名称查询和未注册名称查询。

## Blocked by

- Issue 01：Tool Contract domain model and naming protocol
