状态：ready-for-agent

# Issue 07：Planner clarification feedback workflow

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

实现 Planner 澄清问题的人类反馈链路。当 Planner 无法确定下一步、缺少业务约束或存在多种可选恢复策略时，系统可以创建 planner clarification request；人类回答后，Replanning Loop 带着该回答继续生成受控恢复结果。

完成后，Agent 不需要猜测模糊意图，而是可以把不确定性显式暴露给人类。

## Acceptance criteria

- [ ] Planner 或 Replanning Loop 可以创建 planner clarification request。
- [ ] request 包含明确问题、可选选项、required input schema 和等待原因。
- [ ] provide input decision 可以提交澄清答案。
- [ ] 澄清答案进入 ReplanningRequest human input、PlannerInput constraints 或等价上下文。
- [ ] decision 被消费后触发 REVIEW_COMPLETED、HUMAN_INPUT_REQUIRED 或等价恢复 trigger。
- [ ] Replanning 恢复结果能包含人类回答摘要。
- [ ] 空答案或不符合 schema 的答案被拒绝。
- [ ] 同一个澄清问题重复触发时不会生成重复 pending request。
- [ ] 本 issue 不让 LLM 直接执行未校验的人类输入。
- [ ] 测试覆盖 clarification request、合法回答、非法回答、恢复 trigger、PlannerInput 传递和幂等创建。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
- Issue 03：Decision submission validation and audit sanitization
