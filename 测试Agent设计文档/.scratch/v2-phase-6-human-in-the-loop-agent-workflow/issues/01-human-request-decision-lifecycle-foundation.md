状态：ready-for-agent

# Issue 01：Human request and decision lifecycle foundation

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

建立 Human-in-the-loop 的领域基础，让 Agent 对人类发出的请求和人类返回的决策都成为可查询、可审计、可测试的一等对象。

这个 issue 只交付 request / decision 的最小生命周期、类型、状态、查询和基础校验。它不接入 Replanning，不修改草稿，不生成 memory candidate，也不做 UI。

## Acceptance criteria

- [ ] 定义 HumanReviewRequest 或等价模型，表示 Agent 对人类发出的等待请求。
- [ ] 定义 HumanDecisionRecord 或等价模型，表示人类对请求的结构化响应。
- [ ] request type 至少覆盖 draft review、blocker resolution、high-risk approval、planner clarification、missing input、review completion。
- [ ] request status 至少覆盖 pending、answered、consumed、rejected、cancelled、expired。
- [ ] decision type 至少覆盖 approve、reject、provide input、request changes、promote draft、discard draft、resolve blocker。
- [ ] HumanReviewRequest 包含 task id、可选 plan step id、waiting reason、required input schema、risk level、source trigger、可选 planner decision id、可选 policy reason、metadata 和 timestamps。
- [ ] HumanDecisionRecord 包含 request id、task id、decision type、actor、reason、payload、sanitized payload summary 和 created timestamp。
- [ ] 支持按 task 查询 human requests 和 decision records。
- [ ] 支持按 status 查询 pending requests。
- [ ] 支持按 type 查询 requests。
- [ ] completed task 不接受新的 human request。
- [ ] cancelled task 不接受新的 human request。
- [ ] 测试覆盖模型契约、状态转换、基础查询、非法 task 状态和最小持久化行为。

## Blocked by

None - can start immediately.
