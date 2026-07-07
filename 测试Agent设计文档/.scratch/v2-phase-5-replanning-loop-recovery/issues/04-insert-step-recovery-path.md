状态：ready-for-agent

# Issue 04：Insert-step recovery path

## Parent

V2 Phase 5：Replanning Loop 与异常恢复 PRD

## What to build

支持安全的 INSERT_STEP 恢复路径。Planner 在明确触发器下提出插入恢复步骤，经 PolicyValidator 允许后，系统可以创建新的 pending PlanStep，用来补充失败分析、知识检索或其他 V1 范围内的恢复动作。

这个 issue 要交付可验证的计划变更：例如 PlanStep 失败后插入 ANALYZE_FAILURE，或上下文缺失时插入 RETRIEVE_KNOWLEDGE。

## Acceptance criteria

- [ ] Policy allowed 的 INSERT_STEP decision 可以创建新的 pending PlanStep。
- [ ] 插入步骤必须使用合法 PlanStepType。
- [ ] 插入步骤必须有稳定 step order，且不会破坏已有步骤顺序。
- [ ] 插入步骤保留 goal 或等价可读意图。
- [ ] 插入步骤保留 inputRef 或等价来源信息。
- [ ] 插入步骤可通过 metadata、inputRef 或 ReplanningResult 追踪到 decision id / trigger / source step。
- [ ] 成功历史 PlanStep 不会被重写。
- [ ] RUNNING PlanStep 不会被修改。
- [ ] failed source step 不会被抹除或改成 success。
- [ ] ReplanningResult 返回 APPLIED，并包含 inserted step ids。
- [ ] 支持 plan step failed -> insert ANALYZE_FAILURE 的恢复场景。
- [ ] 支持 context missing -> insert RETRIEVE_KNOWLEDGE 的恢复场景。
- [ ] 测试覆盖插入步骤、顺序、状态、goal、inputRef、audit 和不改写历史。

## Blocked by

- Issue 03：Policy-gated replanning decision handling
