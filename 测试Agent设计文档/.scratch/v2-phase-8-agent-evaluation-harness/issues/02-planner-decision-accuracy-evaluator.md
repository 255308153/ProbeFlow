状态：ready-for-agent

# Issue 02：Planner decision accuracy evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Planner decision accuracy evaluator。系统需要能用 golden fixture 驱动 Controlled Planner，并评估 `PlanDecision` 的 action、proposed step、proposed tool、confidence、risk level、blockers 和 required human input 是否符合预期。

这个 issue 要交付一个可独立运行的 Planner 评估路径，证明 Planner 决策质量可以被固定样例回归，而不是靠人工看日志。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected planner action。
- [ ] golden fixture 可以声明 expected proposed step 或 expected proposed tool。
- [ ] golden fixture 可以声明 expected confidence band，而不是只匹配一个浮点数。
- [ ] golden fixture 可以声明 expected risk level。
- [ ] golden fixture 可以声明 expected blockers。
- [ ] golden fixture 可以声明 expected required human input schema 或关键字段。
- [ ] evaluator 能运行 Controlled Planner 或等价 planner seam，并收集实际 `PlanDecision`。
- [ ] evaluator 能分别评估 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP。
- [ ] evaluator 能识别 planner failed / blocked decision，并输出可读 diagnostic。
- [ ] evaluator 不断言 prompt 文案、私有 helper 或 LLM 原始响应文本。
- [ ] metric result 能说明 action mismatch、tool mismatch、confidence mismatch、risk mismatch、blocker mismatch 或 human input mismatch。
- [ ] planner evaluator 默认使用 fake planner scenario 或 deterministic provider，CI 不依赖真实 LLM。
- [ ] 测试覆盖至少一个通过 fixture、一个 action mismatch fixture、一个 WAIT_FOR_HUMAN fixture 和一个 blocked/failed fixture。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
