状态：ready-for-agent

# Issue 05：High-risk tools and V1 boundary safety policy

## Parent

V2 Phase 2：Tool Contract 与 Agent Policy PRD

## What to build

实现高风险工具与 V1 边界安全策略。HTTP execution 等会产生真实外部影响的能力必须受 readiness、review gate、人工确认和策略白名单控制；同时明确禁止 UI 自动化、Service 直调、DB 直连断言、外部通知、ticket 创建、真实 CI 操作和 MCP/plugin marketplace 等越界能力。

这个 issue 的目标是让 ProbeFlow 的 Agent 边界在进入 Planner 前就足够清晰。

## Acceptance criteria

- [ ] HTTP execution 工具被标记为高风险或更高风险等级。
- [ ] HTTP execution 在缺少 readiness 条件时返回 BLOCKED。
- [ ] HTTP execution 在缺少已审核测试用例或 review gate 通过状态时返回 BLOCKED。
- [ ] HTTP execution 在需要人工确认的策略下返回 REQUIRES_HUMAN_CONFIRMATION。
- [ ] UI automation 类工具名称或能力请求返回 BLOCKED。
- [ ] Service direct call 类工具名称或能力请求返回 BLOCKED。
- [ ] DB direct assertion 类工具名称或能力请求返回 BLOCKED。
- [ ] External notification 类工具名称或能力请求返回 BLOCKED。
- [ ] Ticket creation 类工具名称或能力请求返回 BLOCKED。
- [ ] Real CI operation 类工具名称或能力请求返回 BLOCKED。
- [ ] MCP/plugin marketplace 类工具名称或能力请求返回 BLOCKED。
- [ ] 测试覆盖高风险工具 gating 和所有 V1 边界禁止项。

## Blocked by

- Issue 03：AgentPolicy whitelist and policy decisions
- Issue 04：Tool input schema and precondition validation
