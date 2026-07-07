状态：ready-for-agent

# Issue 06：Planner-safe tool catalog view

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

提供 Planner-safe 工具目录视图。未来 Controlled Planner 应只能看到安全的工具 metadata：工具名称、描述、输入输出摘要、风险等级、执行模式和前置条件说明，不能看到 Java service、repository、Spring bean、数据库、HTTP client 或真实执行入口。

这个 issue 的目标是建立“LLM 看到契约，不看到实现”的安全边界。

## Acceptance criteria

- [ ] 提供面向 Planner 的工具目录视图或导出对象。
- [ ] Planner-safe 视图只包含安全 metadata。
- [ ] Planner-safe 视图包含工具名称、能力分组、简短描述、输入 schema 摘要、输出 schema 摘要、风险等级、执行模式和前置条件说明。
- [ ] Planner-safe 视图不暴露 Java service 类名或实例引用。
- [ ] Planner-safe 视图不暴露 repository、database、entity manager、HTTP client 或 Spring bean 信息。
- [ ] Planner-safe 视图可以按当前 AgentPolicy 过滤不可用工具。
- [ ] Planner-safe 视图可以标明工具是 ALLOWED、REQUIRES_HUMAN_CONFIRMATION 还是 BLOCKED。
- [ ] Planner-safe 视图不执行任何工具。
- [ ] 测试覆盖安全字段导出、敏感实现细节不泄露和按策略过滤。

## Blocked by

- Issue 02：Internal ToolContractRegistry catalog
- Issue 03：AgentPolicy whitelist and policy decisions
- Issue 05：High-risk tools and V1 boundary safety policy
