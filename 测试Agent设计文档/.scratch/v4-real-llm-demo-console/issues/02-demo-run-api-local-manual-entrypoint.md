Status: ready-for-agent

# V4 Issue 02：Demo Run API：本地手动运行入口

## Parent

`/Users/lqc/Downloads/ProbeFlow/测试Agent设计文档/.scratch/v4-real-llm-demo-console/PRD.md`

## What to build

提供一个面向本地手动演示的 Demo Run API。用户可以通过 HTTP 请求选择 demo fixture、provider mode、run profile 和输出偏好，触发一次 V4 demo run，并获得稳定的 demo result view model。

API 应复用 Issue 01 建立的 `DemoRunApplicationService`，不能重新实现第二套 Agent 演示流程。它的职责是把本地演示输入转换为应用服务请求，并把应用服务结果以清晰、可前端消费、可脚本消费的方式返回。

## Acceptance criteria

- [ ] 存在本地 Demo Run API 入口，可以触发一次 fake provider demo run。
- [ ] API 请求支持选择 fixture id、provider mode、run profile、comparison 开关、memory write 开关和输出偏好中的必要字段。
- [ ] API 响应使用 Issue 01 的稳定 demo result schema，而不是暴露内部 harness 对象。
- [ ] 缺失 fixture、非法 provider mode、非法 profile 等输入错误能返回清晰错误。
- [ ] 运行失败时，响应包含可展示的错误区块，能区分配置问题、fixture 问题、LLM 问题或系统问题。
- [ ] API 默认路径不依赖真实 LLM、真实 embedding 或真实外部 HTTP。
- [ ] 有 MVC 层或等价入口测试覆盖成功请求、非法请求、provider mode 和响应脱敏基础行为。

## Blocked by

- `01-demo-run-result-contract-and-fake-baseline.md`

