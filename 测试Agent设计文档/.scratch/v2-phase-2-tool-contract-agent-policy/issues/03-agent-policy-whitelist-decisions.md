状态：ready-for-agent

# Issue 03：AgentPolicy whitelist and policy decisions

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

实现 AgentPolicy 与工具白名单判定能力。给定候选工具、当前工作流模式和任务阶段，系统应返回稳定策略结果：允许执行、需要人工确认或阻止执行，并提供结构化 reason code 和说明。

这个 issue 不执行工具，只回答“这个工具在当前 Agent 策略下是否可被建议或进入执行前校验”。

## Acceptance criteria

- [ ] 定义 AgentPolicy 或等价策略对象，表达工具白名单、工作流模式、当前任务阶段和 V1 边界规则。
- [ ] 定义策略判定结果，至少包含 ALLOWED、REQUIRES_HUMAN_CONFIRMATION、BLOCKED。
- [ ] 策略判定结果包含稳定 reason code。
- [ ] 策略判定结果包含可读说明，方便后续审计和报告展示。
- [ ] 白名单内的低风险只读工具可以返回 ALLOWED。
- [ ] 高风险工具可以返回 REQUIRES_HUMAN_CONFIRMATION。
- [ ] 不在白名单内的工具返回 BLOCKED。
- [ ] 策略判定不依赖真实 LLM 调用、真实网络或 API key。
- [ ] 测试覆盖允许、需要人工确认、阻止和未注册工具等策略结果。

## Blocked by

- Issue 01：Tool Contract domain model and naming protocol
- Issue 02：Internal ToolContractRegistry catalog
