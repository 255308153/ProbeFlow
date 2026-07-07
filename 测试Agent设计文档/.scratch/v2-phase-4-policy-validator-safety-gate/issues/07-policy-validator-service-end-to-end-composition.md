状态：ready-for-agent

# Issue 07：PolicyValidatorService end-to-end composition

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

把 Phase 4 的各类策略组合到统一 PolicyValidatorService 行为中，形成一条完整的 PlannerDecision 安全校验路径。

完成后，调用方只需要提交 PlannerDecision、PlannerInput 和 AgentPolicy，就能得到单一的 PolicyValidationResult，而不用知道内部先校验 Planner status、tool policy、人工确认、V1 边界还是阶段顺序。

## Acceptance criteria

- [ ] PolicyValidatorService 对外提供单一稳定入口。
- [ ] 校验顺序确定且可解释，严重越界或非法状态不会被后续 allowed 分支覆盖。
- [ ] 一个合法低风险 PlannerDecision 能返回 ALLOWED，并携带完整 audit summary。
- [ ] 一个需要人工确认的 PlannerDecision 能返回 REQUIRES_HUMAN_CONFIRMATION，并携带 human input 或 confirmation reason。
- [ ] 一个越界 PlannerDecision 能返回 BLOCKED，并携带 blockers。
- [ ] audit summary 能串联 decisionId、sourceLlmCallId、planner action、proposed tool、validation status 和 reason code。
- [ ] 组合路径不调用 LLM，不执行工具，不写数据库，不修改 Task 或 PlanStep。
- [ ] 测试使用外部行为断言，不依赖私有方法或内部实现顺序。
- [ ] 测试覆盖 allowed、requires human confirmation、blocked 三类端到端结果。
- [ ] 完成后运行后端相关测试。

## Blocked by

- Issue 02：PlannerDecision status and action safety validation
- Issue 03：Proposed tool visibility and AgentPolicy validation
- Issue 04：Human confirmation, risk level and confidence gate
- Issue 05：V1 backend API testing boundary blockers
- Issue 06：Task phase order and precondition validation
