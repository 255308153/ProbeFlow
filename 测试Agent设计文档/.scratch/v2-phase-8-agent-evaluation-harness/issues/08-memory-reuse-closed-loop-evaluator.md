状态：ready-for-agent

# Issue 08：Memory reuse closed-loop evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Memory reuse closed-loop evaluator。系统需要用两段式 golden fixture 证明上一轮任务沉淀的长期记忆，能在下一轮任务中被 UnifiedContextBuilder 召回、被 citation 引用、被 usage record 记录，并能接受 usefulness feedback 调整评分。

这个 issue 要交付 Phase 8 最核心的闭环评估：Agent 不是只写记忆，而是能证明记忆被后续任务复用并产生反馈。

## Acceptance criteria

- [ ] golden fixture 支持 two-stage memory reuse scenario。
- [ ] 第一阶段 fixture 能制造可学习失败、人工反馈或 policy learning note。
- [ ] 第一阶段能通过 MemoryRefinery / AgentMemoryFeedback 或等价链路产生长期记忆。
- [ ] 第二阶段 fixture 能运行新的 context build 或 planner-related flow。
- [ ] evaluator 能检查 expected long-term memory 被召回。
- [ ] evaluator 能检查 expected memory citation 出现在 ContextBundle 或后续结果中。
- [ ] evaluator 能检查 memory usage record 被创建。
- [ ] evaluator 能提交或模拟 positive usefulness feedback。
- [ ] evaluator 能检查 memory confidence、importance、successContribution 或等价评分字段发生合理变化。
- [ ] evaluator 能对 missing recall、missing citation、missing usage record、feedback not applied 输出诊断。
- [ ] evaluator 能覆盖 negative usefulness feedback，证明误导记忆会降权或被标记。
- [ ] two-stage fixture 重复运行不会互相污染。
- [ ] 测试覆盖 memory recall、memory citation、usage record、positive feedback、negative feedback 和重复失败 merge 场景。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
- Issue 04：Context citation usefulness evaluator
- Issue 05：Failure classification accuracy evaluator
