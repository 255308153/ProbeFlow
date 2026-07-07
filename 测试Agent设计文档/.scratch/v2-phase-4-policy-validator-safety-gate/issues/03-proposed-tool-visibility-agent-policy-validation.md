状态：ready-for-agent

# Issue 03：Proposed tool visibility and AgentPolicy validation

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

让 Policy Validator 能校验 Planner 提议的工具是否真实存在、当前可见、符合 AgentPolicy，并复用已有 Tool Contract / AgentPolicy 规则。

完成后，Planner 不能凭空发明工具，不能使用当前 PlannerInput 没有暴露的工具，不能绕过工具白名单、阶段限制、contract blocked 状态、输入 schema 和前置条件检查。

## Acceptance criteria

- [ ] Planner 提议的 tool name 必须能被解析为合法工具名称。
- [ ] 未注册工具返回 BLOCKED，并使用稳定 unknown tool reason code。
- [ ] 工具存在但不在 PlannerInput available tools 中时返回 BLOCKED。
- [ ] 工具不在 AgentPolicy 白名单中时返回 BLOCKED。
- [ ] 工具不允许在当前 AgentTaskPhase 使用时返回 BLOCKED。
- [ ] contract 标记为 blocked 的工具返回 BLOCKED。
- [ ] 工具输入缺少必填字段时返回 BLOCKED。
- [ ] 工具输入类型错误或枚举值非法时返回 BLOCKED。
- [ ] 工具缺少 required preconditions 时返回 BLOCKED。
- [ ] 工具级校验复用已有 AgentPolicyService 或等价唯一事实来源，避免重复实现一套不一致规则。
- [ ] 合法工具在当前阶段、白名单、schema 和 preconditions 都满足时可以通过。
- [ ] 测试覆盖未知工具、不可见工具、未白名单工具、阶段不匹配、schema 错误、缺少 precondition 和合法工具路径。

## Blocked by

- Issue 01：Policy validation result contract and audit summary
