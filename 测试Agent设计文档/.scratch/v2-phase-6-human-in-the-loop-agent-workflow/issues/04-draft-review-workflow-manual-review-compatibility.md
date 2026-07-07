状态：ready-for-agent

# Issue 04：Draft review workflow and ManualReviewGate compatibility

## Parent

V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## What to build

把测试用例草稿 review 接入 Human-in-the-loop 工作流。Agent 可以创建 draft review request，人类可以通过 promote、discard、request changes 等 decision 处理草稿，并且现有 ManualReviewGate 行为保持兼容。

完成后，草稿 review 不再只是隐含在 Task metadata 中，而是有 request、decision、草稿状态和恢复信号共同组成的可审计链路。

## Acceptance criteria

- [ ] 可以为 pending test case drafts 创建 draft review HumanReviewRequest。
- [ ] draft review request 记录 pending draft ids 和等待原因。
- [ ] promote draft decision 可以把指定 draft 推进为正式 test case 或等价已采纳状态。
- [ ] discard draft decision 可以明确丢弃指定 draft。
- [ ] request changes decision 可以记录修改要求，并保持任务等待或触发后续恢复。
- [ ] decision reason 记录人类为什么采纳、丢弃或要求修改。
- [ ] draft review 完成后 request status 进入 answered / consumed 或等价终态。
- [ ] ManualReviewGate 能识别新工作流状态，并保持 V1 半自动 review 行为兼容。
- [ ] 没有 pending draft 时不会创建重复 draft review request。
- [ ] 本 issue 不实现前端 review 页面，不重写 V1 draft generation。
- [ ] 测试覆盖 request 创建、promote、discard、request changes、ManualReviewGate 兼容性和重复 request 防护。

## Blocked by

- Issue 01：Human request and decision lifecycle foundation
- Issue 03：Decision submission validation and audit sanitization
