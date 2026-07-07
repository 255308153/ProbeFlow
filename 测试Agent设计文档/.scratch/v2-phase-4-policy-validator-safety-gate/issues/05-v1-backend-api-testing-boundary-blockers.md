状态：ready-for-agent

# Issue 05：V1 backend API testing boundary blockers

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

为 Policy Validator 补齐 V1 后端 API 测试边界拦截。V2 Phase 4 仍然只做后端 API 测试 Agent，不允许 Planner 建议 UI 自动化、浏览器自动化、service 直调、数据库直接断言、外部通知、工单系统、真实 CI 或动态扩权工具。

完成后，面试讲解中可以明确说明：ProbeFlow 不是让 LLM 任意调用工具，而是在 Java 侧用确定性边界保护 Agent 能力范围。

## Acceptance criteria

- [ ] UI 自动化相关工具被 BLOCKED。
- [ ] 浏览器自动化相关工具被 BLOCKED。
- [ ] service 直调相关工具被 BLOCKED。
- [ ] 数据库直接断言相关工具被 BLOCKED。
- [ ] 通知、邮件、Slack、Webhook 等外部通知工具被 BLOCKED。
- [ ] GitHub issue、Jira 等外部工单工具被 BLOCKED。
- [ ] 真实 CI 触发工具被 BLOCKED。
- [ ] MCP、插件市场或动态扩权相关工具被 BLOCKED。
- [ ] 每类 V1 boundary blocker 都有稳定 reason code 或稳定分类。
- [ ] 边界拦截优先于普通 allowed 路径，不能被白名单误放行。
- [ ] 测试覆盖所有禁止类别，并覆盖一个合法后端 API 测试工具不被误拦截。

## Blocked by

- Issue 03：Proposed tool visibility and AgentPolicy validation
