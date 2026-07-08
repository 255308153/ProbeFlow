Status: ready-for-agent

# V4 Issue 03：Demo Console 第一版：可运行页面与核心区块

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

实现一个轻量本地 Demo Console，让用户打开页面后可以直接运行 ProbeFlow Agent Demo。第一屏应是可运行体验，而不是营销页：选择 fixture、选择 provider mode、点击运行、查看运行状态和结构化结果。

页面的核心价值是帮助用户在面试或协作演示中讲清楚 Agent 设计。它应按 Agent 工作流组织结果区块，例如 Plan、Context、Tools、Suite、Execution、Failure Analysis、Memory Feedback、Evaluation，并能展示每个区块的状态和关键摘要。

## Acceptance criteria

- [ ] 存在一个可本地打开或随后端启动访问的 Demo Console 页面。
- [ ] 页面第一屏包含 fixture 选择、provider mode 选择、run 按钮、运行状态和结果区域。
- [ ] 页面调用 Issue 02 的 Demo Run API，不直接调用 harness 或后端内部对象。
- [ ] 页面能展示 Plan、Context、Tools、Suite、Execution、Failure Analysis、Memory Feedback、Evaluation 的结构化摘要。
- [ ] 页面能清楚区分 fake、real、comparison 三种 provider mode 的输出来源。
- [ ] 点击或展开 step、context item、tool call 时，可以看到必要详情摘要。
- [ ] 页面不包含登录、租户、RBAC、完整测试管理后台等生产级前端能力。
- [ ] 有基础前端或端到端测试覆盖页面可加载、核心控件存在、调用 API 后主要区块可渲染。

## Blocked by

- `02-demo-run-api-local-manual-entrypoint.md`

