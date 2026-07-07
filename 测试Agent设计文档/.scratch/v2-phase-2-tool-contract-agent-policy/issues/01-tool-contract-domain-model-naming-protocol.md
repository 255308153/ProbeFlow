状态：ready-for-agent

# Issue 01：Tool Contract domain model and naming protocol

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

建立 V2 Phase 2 的 Tool Contract 领域模型与工具命名协议。系统需要有稳定的工具名称、工具契约、输入 schema、输出 schema、风险等级、执行模式和前置条件表达方式，让后续工具目录、AgentPolicy、Planner-safe 视图和边界测试都基于同一套协议工作。

这个 issue 只建立契约和核心类型，不实现完整工具目录，不执行任何工具，也不接入 Controlled Planner。

## Acceptance criteria

- [ ] 定义稳定工具名称协议，避免后续 Planner 使用自由文本猜工具。
- [ ] 定义工具契约，包含工具名称、能力分组、描述、输入 schema、输出 schema、前置条件、风险等级、执行模式、人工确认要求和标签。
- [ ] 定义工具输入 schema，用于表达必需字段、可选字段、字段含义和基本类型。
- [ ] 定义工具输出 schema，用于表达工具结果摘要和后续 StepOutcome 可消费的信息。
- [ ] 定义工具风险等级，至少覆盖 LOW、MEDIUM、HIGH、CRITICAL。
- [ ] 定义工具执行模式，至少覆盖允许自动执行、需要人工确认、禁止自动执行。
- [ ] 定义工具前置条件，能表达 Task、ApiSpec、ContextBundle、TestCaseDraft、ExecutionRecord、Observation、Report 等状态依赖。
- [ ] Tool Contract 领域模型不依赖真实 Java service、repository、database、HTTP client 或 Spring bean。
- [ ] 测试覆盖工具契约创建、字段完整性、风险等级、执行模式和前置条件表达。

## Blocked by

None - can start immediately.
