状态：ready-for-agent

# Issue 01：Policy validation result contract and audit summary

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

建立 Policy Validator 的最小可用入口和结果契约。系统需要能接收 PlannerDecision、PlannerInput 和 AgentPolicy，返回结构化的 PolicyValidationResult，并能生成审计摘要。

这个 issue 要交付一条最小可验证路径：安全的 CONTINUE decision 经过 Policy Validator 后返回 allowed 结果，并包含稳定 status、reason code、message、blockers 和 audit summary。它不实现完整工具校验、不做阶段顺序校验、不执行任何工具。

## Acceptance criteria

- [ ] 定义 PolicyValidationStatus 或等价状态，至少表达 ALLOWED、REQUIRES_HUMAN_CONFIRMATION、BLOCKED。
- [ ] 定义 PolicyValidationReasonCode 或等价 reason code，避免测试依赖易变文案。
- [ ] 定义 PolicyValidationResult，包含 status、reason code、message、blockers、decision id、planner action、proposed tool、source llm call id 等审计所需信息。
- [ ] 定义 PolicyValidationRequest 或等价输入对象，承载 PlannerDecision、PlannerInput 和 AgentPolicy。
- [ ] 新增 PolicyValidatorService 或等价应用层入口。
- [ ] 安全的 CONTINUE PlannerDecision 在最小输入下返回 ALLOWED。
- [ ] PolicyValidationResult 能生成 audit summary，并能关联 decisionId、sourceLlmCallId、planner action、validation status 和 reason code。
- [ ] 空 request 或缺少 PlannerDecision 时有明确失败行为。
- [ ] 不调用 LLM，不执行工具，不修改 Task、PlanStep、TestCase、Memory 或 Report。
- [ ] 测试覆盖 allowed 最小路径、audit summary、reason code 和空输入失败行为。

## Blocked by

None - can start immediately.
