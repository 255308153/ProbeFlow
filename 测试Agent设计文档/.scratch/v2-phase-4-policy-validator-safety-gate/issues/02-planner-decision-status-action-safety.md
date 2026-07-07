状态：ready-for-agent

# Issue 02：PlannerDecision status and action safety validation

## Parent

V2 Phase 4：Policy Validator 与安全拦截 PRD

## What to build

扩展 Policy Validator，使它能根据 PlannerDecision 的 status 和 action 做第一层安全判断。系统必须明确区分 proposed、blocked、failed、continue、insert step、replan、wait for human 和 stop，防止异常或不可执行的 Planner 建议进入执行层。

这个 issue 只处理 PlannerDecision 自身的状态与动作合法性，不做完整工具策略、不做 V1 禁止边界、不做阶段顺序校验。

## Acceptance criteria

- [ ] PROPOSED 状态之外的 PlannerDecision 默认不能作为可执行建议通过。
- [ ] BLOCKED PlannerDecision 返回 BLOCKED validation result，并保留 Planner blockers。
- [ ] FAILED PlannerDecision 返回 BLOCKED validation result，明确表达 planner failure 不能执行。
- [ ] WAIT_FOR_HUMAN action 返回 REQUIRES_HUMAN_CONFIRMATION 或等价人工输入状态。
- [ ] WAIT_FOR_HUMAN 缺少 required human input 时返回 BLOCKED。
- [ ] INSERT_STEP action 缺少 proposed step 时返回 BLOCKED。
- [ ] REPLAN action 不触发工具执行，只作为后续 Replanning Loop 信号返回安全结果。
- [ ] STOP action 只允许作为安全终止建议，不允许携带工具执行意图。
- [ ] CONTINUE action 在无新增工具调用时保持 allowed 路径。
- [ ] 每个状态和 action 分支都有稳定 reason code。
- [ ] 测试覆盖 PROPOSED、BLOCKED、FAILED，以及 CONTINUE、INSERT_STEP、REPLAN、WAIT_FOR_HUMAN、STOP。

## Blocked by

- Issue 01：Policy validation result contract and audit summary
