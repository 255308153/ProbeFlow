状态：ready-for-agent

# Issue 06：Task phase order and precondition validation

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

让 Policy Validator 能基于 PlannerInput 中的 task state、current phase、last step outcome 和 context bundle summary 校验任务阶段顺序与关键前置条件。

完成后，Planner 不能跳过 API 分析、上下文构建、用例设计、HTTP 执行、失败分析和报告生成的基本顺序，也不能在缺少关键上下文时推进高风险步骤。

## Acceptance criteria

- [ ] 未完成 API 分析或缺少 API spec 时，不能进入依赖 API spec 的后续步骤。
- [ ] 未完成上下文构建或缺少 context bundle 时，不能进入依赖知识上下文的测试设计建议。
- [ ] 未完成测试用例设计或缺少 approved/draft test cases 时，不能进入 HTTP execution。
- [ ] 无执行结果或失败信号时，不能进入 failure analysis。
- [ ] 报告阶段前必须具备足够任务过程数据，否则返回 BLOCKED。
- [ ] AgentTaskPhase 与 proposed tool capability group 不匹配时返回 BLOCKED。
- [ ] 阶段顺序错误返回稳定 reason code 和可读 blocker。
- [ ] 只实现 Phase 4 需要的轻量顺序规则，不重写完整任务编排引擎。
- [ ] 合法阶段顺序下的工具建议可以继续进入后续策略判断。
- [ ] 测试覆盖提前 HTTP execution、无失败信号的 failure analysis、信息不足的 report、合法顺序路径。

## Blocked by

- Issue 02：PlannerDecision status and action safety validation
- Issue 03：Proposed tool visibility and AgentPolicy validation
