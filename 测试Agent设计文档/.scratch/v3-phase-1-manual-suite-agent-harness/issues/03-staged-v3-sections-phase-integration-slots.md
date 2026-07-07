状态：ready-for-agent

# Issue 03：Staged V3 sections and phase integration slots

## Parent

ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## What to build

在 harness report 中补齐 V3 后续能力的统一展示槽位：generated suite draft、variable audit、failure analysis、memory feedback summary 和 evaluation comparison。V3-1 可以先使用 fixture/staged/pending-runtime summary，但必须清楚标记来源，避免误导为完整智能链路能力已经完成。

这个 issue 的价值是把 V3-2 到 V3-6 的接入位置提前固定下来，让后续同事每完成一个 phase 都能替换同一个 report section，而不是各自输出一套临时结果。

## Acceptance criteria

- [ ] Harness run result 包含 generated suite draft section。
- [ ] Generated suite draft section 能展示 scenario name、step order、step name、critical 标记、source refs 和 staged/fixture source marker。
- [ ] Harness run result 包含 variable audit section。
- [ ] Variable audit section 在 V3-1 可以标记为 pending-runtime 或 staged，但必须展示后续要接入的 producer、consumer、target scope、target key 和 audit event 摘要位置。
- [ ] Harness run result 包含 failure analysis section。
- [ ] Failure analysis section 在 V3-1 可以标记为 staged，但必须展示 root step、affected steps、failure type、evidence 和 next suggestion 的摘要位置。
- [ ] Harness run result 包含 memory feedback summary section。
- [ ] Memory feedback summary 在 V3-1 可以标记为 staged，但必须展示 candidate count、source type、tags、confidence 和 learning note 摘要位置。
- [ ] Harness run result 包含 evaluation comparison section。
- [ ] Evaluation comparison 在 V3-1 可以标记为 not-run 或 staged，但必须展示 provider mode、fixture id、capability tags 和 expected markers 的位置。
- [ ] JSON report 中每个 staged section 都包含明确 source marker，例如 real、fixture、staged 或 pending-runtime。
- [ ] Markdown report 中每个 staged section 都用人类可读文案说明哪些能力尚待 V3-2 到 V3-6 接入。
- [ ] 后续 phase 替换 staged section 时不需要新增另一套 harness 入口。
- [ ] 测试覆盖每个 section 的存在、source marker、JSON/Markdown 一致性和 stage marker 不误报为 real。

## Blocked by

- Issue 01：Harness contract, fixture registry and smoke run foundation
