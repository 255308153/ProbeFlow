状态：ready-for-agent

# Issue 06：PlanDecision parser and safe fallback

## Parent

V2 Phase 3：Controlled Planner PRD

## What to build

实现 LLM planner 结构化输出解析与安全 fallback。系统应把 LLM 输出解析成 PlanDecision；当输出不是结构化格式、缺少必需字段、包含未知 action 或不合法字段时，返回安全 failed/blocked decision，并且不执行任何动作。

这个 issue 的核心是让自由文本不能驱动系统行为。

## Acceptance criteria

- [ ] 实现 PlanDecisionParser 或等价解析组件。
- [ ] 支持解析结构化 CONTINUE 决策。
- [ ] 支持解析结构化 INSERT_STEP 决策。
- [ ] 支持解析结构化 REPLAN 决策。
- [ ] 支持解析结构化 WAIT_FOR_HUMAN 决策，并校验 required human input。
- [ ] 支持解析结构化 STOP 决策。
- [ ] 未知 action 返回安全 failed/blocked decision。
- [ ] 缺少必需字段返回安全 failed/blocked decision。
- [ ] 非结构化自由文本返回安全 failed/blocked decision。
- [ ] 未知 tool name 不会被执行，应保留为待未来 Policy Validator 拒绝的无效建议或 blocked reason。
- [ ] parser 失败不修改 Task，不插入 PlanStep，不调用任何工具。
- [ ] 测试覆盖解析成功、未知 action、缺字段、自由文本、WAIT_FOR_HUMAN 缺少输入说明等场景。

## Blocked by

- Issue 01：Planner decision domain model
- Issue 04：ControlledPlannerService application entrypoint
- Issue 05：LLM-backed planner call path
