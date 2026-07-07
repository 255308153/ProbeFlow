状态：ready-for-agent

# Issue 03：Tool selection and policy validity evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Tool selection and policy validity evaluator。系统需要复用现有 ToolContract、AgentPolicy 和 PolicyValidator 真实逻辑，评估 Planner 提出的工具是否可见、是否在白名单内、是否满足前置条件、是否需要人工确认，以及是否遵守 V1 边界。

这个 issue 的目标是证明 Agent 的工具调用不是 LLM 自由选择，而是可评估、可解释、可拦截的受控决策。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected tool name。
- [ ] golden fixture 可以声明 expected policy status，例如 allowed、blocked、requires human confirmation 或等价状态。
- [ ] golden fixture 可以声明 expected policy rejection reason。
- [ ] evaluator 复用 ToolContractRegistry、AgentPolicyService、PolicyValidatorService 或等价真实策略链路。
- [ ] evaluator 能识别 unknown tool。
- [ ] evaluator 能识别 tool not visible to planner。
- [ ] evaluator 能识别 tool not whitelisted。
- [ ] evaluator 能识别 missing precondition。
- [ ] evaluator 能识别 high-risk tool requires human confirmation。
- [ ] evaluator 能识别 V1 boundary blocked，例如 UI 自动化、Service 直调、DB 直连断言。
- [ ] metric result 能输出工具名、policy status、reason code、expected/actual 差异和 diagnostic message。
- [ ] evaluator 不在评估层重新实现一套与 PolicyValidator 分叉的策略逻辑。
- [ ] 测试覆盖 allowed、blocked、requires human confirmation、unknown tool、not visible、missing precondition 和 V1 boundary blocked。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
