状态：ready-for-agent

# Issue 06：High-risk approval and rejection workflow

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

实现高风险 Agent 行为的人工确认链路。PolicyValidator 或 Replanning Loop 认为某个动作需要人类确认时，系统创建 high-risk approval request；人类 approve 后可以恢复安全路径，reject 后必须安全停止或重规划，并把拒绝原因带入 PlannerInput。

这个 issue 的重点是可信 Agent 的安全阀：LLM 或 Planner 不能绕过人工确认继续执行高风险动作。

## Acceptance criteria

- [ ] policy validation 需要人工确认时可以创建 high-risk approval request。
- [ ] request 记录 risk level、policy reason、source trigger 和关联 planner decision / plan step 摘要。
- [ ] approve decision 可以把确认信号写入可被 Replanning 消费的上下文。
- [ ] reject decision 可以让任务安全停止、继续等待或触发受控重规划。
- [ ] reject reason 必须进入后续 PlannerInput、decision summary 或等价上下文。
- [ ] 高风险 request 未被 approve 前，相关动作不能被执行。
- [ ] 已拒绝 request 不允许通过重复提交 approve 绕过安全结果。
- [ ] 高风险相关 audit summary 不泄露敏感 payload。
- [ ] 本 issue 不引入复杂权限系统；actor 字段足以支撑本阶段审计。
- [ ] 测试覆盖 high-risk request 创建、approve 恢复信号、reject 安全行为、拒绝原因传递和 stale decision 拒绝。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
- Issue 03：Decision submission validation and audit sanitization
