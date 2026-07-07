状态：ready-for-agent

# Issue 04：Tool input schema and precondition validation

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

实现工具输入 schema 与前置条件校验。系统应能在工具进入执行前发现未注册工具、缺少必需输入、输入字段不合法、缺少 Task/ApiSpec/TestCaseDraft/ExecutionRecord 等状态依赖，并返回明确 BLOCKED 策略结果。

这个 issue 的重点是把“工具能不能运行”的结构性判断前置化，为后续 PlannerDecision 校验打基础。

## Acceptance criteria

- [ ] 支持校验工具输入是否包含必需字段。
- [ ] 支持校验工具输入字段是否符合契约中的基本类型或枚举约束。
- [ ] 支持校验工具前置条件是否满足。
- [ ] 缺少 Task 等基础状态时返回 BLOCKED。
- [ ] 缺少 ApiSpec 时，依赖 API 分析结果的工具返回 BLOCKED。
- [ ] 缺少 TestCaseDraft 或 review gate 状态时，依赖已审核草稿的工具返回 BLOCKED。
- [ ] 缺少 ExecutionRecord 或 failure signal 时，failure analysis 工具返回 BLOCKED。
- [ ] schema 校验和前置条件校验返回结构化 reason code。
- [ ] 校验失败时不触发任何真实工具执行。
- [ ] 测试覆盖输入缺失、输入非法、前置条件缺失和校验通过场景。

## Blocked by

- Issue 01：Tool Contract domain model and naming protocol
- Issue 02：Internal ToolContractRegistry catalog
- Issue 03：AgentPolicy whitelist and policy decisions
