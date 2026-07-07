状态：ready-for-agent

# Issue 03：Decision submission validation and audit sanitization

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

实现人类提交 decision 的统一入口。系统需要校验 request 仍可回答、校验 payload 结构、记录不可覆盖的 decision record，并在审计摘要中屏蔽 token、secret、cookie 等敏感字段。

这个 issue 交付 decision submission 的安全边界，但不把 decision 接入 draft review、blocker resolution、high-risk approval 或 Planner 恢复路径。

## Acceptance criteria

- [ ] 新增 HumanInTheLoopApplicationService、HumanFeedbackApplicationService 或等价应用层入口提交 decision。
- [ ] pending request 可以接受合法 decision 并生成 HumanDecisionRecord。
- [ ] 已 consumed、cancelled、expired、rejected 的 request 拒绝再次提交 decision。
- [ ] decision payload 按 request required input schema 或等价规则验证。
- [ ] 非法 payload 有明确失败结果，不污染 task metadata 或 decision history。
- [ ] decision actor 必须记录，空 actor 有明确失败行为或默认系统约定。
- [ ] decision reason 可以记录，并进入后续可审计摘要。
- [ ] decision history 不被覆盖；重复提交不会覆盖旧 decision record。
- [ ] decision consumption 支持幂等语义，重复消费不会重复触发后续动作。
- [ ] audit summary 对 token、secret、cookie、authorization、password 等敏感字段做 masking。
- [ ] request、decision、task 状态变更位于事务边界内。
- [ ] 本 issue 不调用 LLM、不触发 Replanning、不写长期记忆。
- [ ] 测试覆盖合法提交、schema 校验、stale request 拒绝、敏感字段脱敏、幂等消费和事务边界。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
