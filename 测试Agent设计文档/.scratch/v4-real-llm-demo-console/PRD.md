状态：ready-for-agent

# ProbeFlow V4：真实 LLM 与 Demo Console PRD

## Problem Statement

ProbeFlow 已经完成 V1 的确定性 API 测试后端闭环、V2 的 Agent Core，以及 V3 的智能链路测试 Agent 闭环。

截至 V3-6，系统已经可以用 deterministic fake provider 和 fake HTTP gateway 演示：

```text
Business Flow Discovery
-> Dependency Linker / SUITE draft
-> ExecutionContext / variable audit
-> Suite Failure Analysis
-> Memory Feedback
-> Evaluation Comparison
-> Markdown / JSON report
```

这证明 ProbeFlow 的 Agent 架构已经成立：它能理解接口链路、执行链路、解释失败、沉淀经验，并用评估证明 Agent 行为。

但从用户和面试展示角度看，当前还有三个明显问题：

- 系统主要通过命令行 harness 和测试用例展示能力，非开发者很难手动体验。
- 默认仍是 fake LLM，虽然稳定可测，但无法证明真实 LLM 接入后的规划、分析和报告效果。
- 没有简单前端时，用户很难一眼看到 Agent 的计划、上下文、工具调用、变量审计、失败分析、Memory Feedback 和评估对比。

用户现在需要的是一个 V4 演示产品化阶段：

```text
我可以在本地打开一个简单页面，
选择一个 demo fixture，
选择 fake 或 real LLM 模式，
点击运行，
看到 Agent 完整链路、上下文、工具调用、失败分析、记忆反馈和报告。
```

V4 的目标不是做完整商业化前端，也不是进入 RAG / Memory Pro。V4 要把已有后端 Agent 闭环变成“真实可手动演示”的产品形态，让用户在面试、同事协作和项目介绍中可以清楚展示：

- Agent 如何规划。
- Agent 如何使用工具。
- Agent 如何组织上下文。
- Agent 如何解释链路失败。
- Agent 如何把失败经验写入 Memory。
- Fake LLM 与 Real LLM 的行为差异在哪里。

## Solution

新增 V4 真实 LLM 与 Demo Console 能力。

V4 的核心链路是：

```text
Demo Console
-> Demo Run API
-> DemoRunApplicationService
-> Manual Suite Agent Harness
-> LlmProvider / Controlled Planner / Tool Contract / Unified Context
-> SUITE draft / execution / variable audit / failure analysis / memory feedback
-> Evaluation comparison
-> Demo report JSON / Markdown / UI view model
```

V4 分成四个能力面：

第一，真实 LLM 手动实验模式。

- 保留 fake provider 作为默认模式。
- 在显式配置和显式运行 profile 下启用真实 LLM。
- 真实 LLM 通过现有 LLM provider seam 接入，不绕过 LLM 审计、脱敏、策略和调用日志。
- 未配置 key、模型、超时、成本限制时，真实 LLM 模式必须清晰失败。
- 普通 CI、默认测试和默认 demo run 不依赖真实 LLM。

第二，Demo Run API。

- 提供一个面向手动演示的后端入口。
- 用户可以选择 fixture、provider mode、run profile 和是否启用 fake/real 对比。
- API 返回结构化 demo result，包含 run 状态、provider 摘要、计划摘要、上下文摘要、工具调用摘要、SUITE 草稿、执行结果、变量审计、失败分析、Memory Feedback、评估对比和报告引用。
- API 不暴露内部实现细节，不要求前端知道 harness 内部对象。

第三，简单 Demo Console。

- 提供本地轻量前端，不做完整产品后台。
- 第一屏就是可运行的 Agent Demo，而不是营销页。
- 页面支持选择 fixture、选择 fake / real / comparison 模式、触发运行、查看运行状态和结果。
- 页面重点展示 Agent 设计，而不是只展示最终报告。
- 页面必须清楚标记哪些内容来自 fake provider，哪些内容来自 real LLM，哪些内容来自真实应用服务，哪些内容来自 demo fixture。

第四，LLM / Fake 对比与演示报告。

- 同一个 fixture 可以跑 fake baseline 和 real LLM run。
- 对比计划步骤、工具选择、失败分类、root cause、next suggestion、Memory Feedback、报告摘要和风险提示。
- 输出 JSON 与 Markdown artifact，方便没有前端时仍能查看。
- 对比报告不以“真实 LLM 一定更好”为前提，而是展示差异、风险和人工判断点。

V4 的最高测试 seam 是 `DemoRunApplicationService`。真实 LLM 的变化留在现有 `LlmProvider` seam 后面；前端只通过 Demo Run API 消费稳定 view model；Manual Suite Agent Harness 继续作为 V3 能力的后端演示承载层。

## User Stories

1. As an API 测试 Agent 用户, I want 在本地打开一个 Demo Console, so that 我不用读命令行输出也能体验 ProbeFlow。
2. As an API 测试 Agent 用户, I want 选择一个内置 demo fixture, so that 我可以快速运行一条稳定的演示链路。
3. As an API 测试 Agent 用户, I want 默认使用 fake provider 运行 demo, so that 我不配置真实 LLM 也能验证系统。
4. As an API 测试 Agent 用户, I want 显式选择 real LLM 模式, so that 我可以测试真实模型下的 Agent 行为。
5. As an API 测试 Agent 用户, I want 未配置真实 LLM 时看到清晰错误, so that 我知道缺少 key、模型或开关。
6. As an API 测试 Agent 用户, I want real LLM 模式不会自动进入默认 CI, so that 测试结果不会因为外部模型波动而不稳定。
7. As an API 测试 Agent 用户, I want 一键运行 demo, so that 我能在面试中快速展示项目。
8. As an API 测试 Agent 用户, I want 运行后看到 run status, so that 我知道 demo 是 running、completed、failed 还是 rejected。
9. As an API 测试 Agent 用户, I want 看到 provider mode, so that 我能区分 fake、real 和 comparison 运行。
10. As an API 测试 Agent 用户, I want 看到 Agent plan, so that 我能解释 Agent 是如何拆解任务的。
11. As an API 测试 Agent 用户, I want 看到 tool calls, so that 我能解释 Agent 调用了哪些能力。
12. As an API 测试 Agent 用户, I want 看到 tool policy 决策, so that 我能解释系统如何防止越权调用。
13. As an API 测试 Agent 用户, I want 看到 Unified Context 摘要, so that 我能解释上下文不是简单拼 prompt。
14. As an API 测试 Agent 用户, I want 看到 API context, so that 我能知道 Agent 理解了哪些接口结构。
15. As an API 测试 Agent 用户, I want 看到 Knowledge RAG context, so that 我能知道 Agent 引用了哪些文档知识。
16. As an API 测试 Agent 用户, I want 看到 Long-term Memory context, so that 我能知道 Agent 复用了哪些历史经验。
17. As an API 测试 Agent 用户, I want 看到 task memory, so that 我能知道本次任务中间状态如何延续。
18. As an API 测试 Agent 用户, I want 看到 SUITE draft, so that 我能解释系统生成了怎样的接口链路。
19. As an API 测试 Agent 用户, I want 看到每个 suite step, so that 我能解释接口顺序、依赖关系和关键步骤。
20. As an API 测试 Agent 用户, I want 看到 variable references, so that 我能解释 `${suite.xxx}` 或 `${step.xxx}` 如何流转。
21. As an API 测试 Agent 用户, I want 看到 extract rules, so that 我能解释系统如何从响应提取变量。
22. As an API 测试 Agent 用户, I want 看到 execution result, so that 我能知道每个 step 成功、失败或跳过。
23. As an API 测试 Agent 用户, I want 看到 variable audit, so that 我能定位变量解析、提取和写回的问题。
24. As an API 测试 Agent 用户, I want 看到 runtime diagnostics, so that 我能理解失败现场。
25. As an API 测试 Agent 用户, I want 看到 suite failure analysis, so that 我能知道 root cause 是哪个 step。
26. As an API 测试 Agent 用户, I want 看到 affected downstream steps, so that 我能知道一个失败影响了哪些后续接口。
27. As an API 测试 Agent 用户, I want 看到 next suggestion, so that 我能知道系统建议如何恢复。
28. As an API 测试 Agent 用户, I want 看到 replanning handoff, so that 我能解释 Agent 如何进入下一轮计划。
29. As an API 测试 Agent 用户, I want 看到 human-in-the-loop handoff, so that 我能解释系统何时需要人工确认。
30. As an API 测试 Agent 用户, I want 看到 memory feedback candidate, so that 我能解释失败经验如何进入记忆系统。
31. As an API 测试 Agent 用户, I want 看到 memory feedback status, so that 我能知道经验被 accepted、rejected 还是 deduped。
32. As an API 测试 Agent 用户, I want 看到 memory evidence, so that 我能证明记忆不是凭空生成。
33. As an API 测试 Agent 用户, I want 看到 evaluation comparison, so that 我能展示 Agent 行为有评估标准。
34. As an API 测试 Agent 用户, I want 看到 fake baseline, so that 我能证明默认路径稳定可复现。
35. As an API 测试 Agent 用户, I want 看到 real LLM run, so that 我能评估真实模型带来的变化。
36. As an API 测试 Agent 用户, I want 看到 fake 与 real 的差异, so that 我能讲清楚模型不是黑盒魔法。
37. As an API 测试 Agent 用户, I want 看到 LLM 调用摘要, so that 我能知道调用了几次、哪个用途、是否成功。
38. As an API 测试 Agent 用户, I want 隐藏 secret 和 token, so that demo 不泄漏敏感信息。
39. As an API 测试 Agent 用户, I want 失败时看到清晰错误区块, so that 我能判断是配置问题、LLM 问题、fixture 问题还是系统问题。
40. As an API 测试 Agent 用户, I want demo report 能导出 Markdown, so that 我可以把结果贴到文档或面试材料中。
41. As an API 测试 Agent 用户, I want demo report 能导出 JSON, so that 后续可以自动评测或回放。
42. As an API 测试 Agent 用户, I want Demo Console 不需要复杂账号系统, so that 本地演示启动简单。
43. As an API 测试 Agent 用户, I want Demo Console 只服务本地演示, so that 它不会被误认为生产管理后台。
44. As an API 测试 Agent 用户, I want 页面直接展示可运行体验, so that 第一眼就能看到 Agent 能力。
45. As an API 测试 Agent 用户, I want 页面视觉简洁、信息密度合理, so that 面试展示时不会显得像玩具。
46. As an API 测试 Agent 用户, I want 页面区分 plan、context、tools、suite、failure、memory、evaluation 几个区域, so that 我可以按 Agent 设计顺序讲解。
47. As an API 测试 Agent 用户, I want 每个区域都有结构化状态, so that 我能快速判断哪一步出问题。
48. As an API 测试 Agent 用户, I want 点击某个 step 看到详情, so that 我能深入解释链路变量和失败原因。
49. As an API 测试 Agent 用户, I want 点击某个 context item 看到来源, so that 我能说明 RAG 和 Memory 的来源治理。
50. As an API 测试 Agent 用户, I want 点击某个 tool call 看到输入输出摘要, so that 我能证明工具调用可审计。
51. As an API 测试 Agent 用户, I want Demo API 返回稳定 schema, so that 前端和后续脚本都能复用。
52. As an API 测试 Agent 用户, I want Demo API 能复用现有 harness, so that 不出现第二套演示流程。
53. As an API 测试 Agent 用户, I want Demo API 能明确 run profile, so that fake、real、comparison 的行为边界清楚。
54. As an API 测试 Agent 用户, I want 真实 LLM 超时后可以优雅失败, so that demo 不会卡死。
55. As an API 测试 Agent 用户, I want 真实 LLM 返回无效结构时能展示 parse failure, so that 我能解释结构化输出校验。
56. As an API 测试 Agent 用户, I want 真实 LLM 被 policy 拦截时能展示原因, so that 我能说明 Agent 安全设计。
57. As an API 测试 Agent 用户, I want comparison 模式不会污染长期记忆, so that 对比实验不会影响正常 demo 数据。
58. As an API 测试 Agent 用户, I want real LLM 模式的记忆写入必须可控, so that 模型输出不会无意污染长期记忆。
59. As an API 测试 Agent 用户, I want demo artifact 有 runId, so that 我可以追踪一次演示结果。
60. As an API 测试 Agent 用户, I want demo artifact 有 schemaVersion, so that 后续升级时可以兼容。
61. As an API 测试 Agent 用户, I want demo artifact 记录 fixtureId, so that 我知道演示基于哪个样例。
62. As an API 测试 Agent 用户, I want demo artifact 记录 usesRealLlm, so that 我能区分真实模型和 fake 结果。
63. As an API 测试 Agent 用户, I want demo artifact 记录 usesExternalHttp, so that 我能证明 demo 没有误打真实服务。
64. As an API 测试 Agent 用户, I want V4 文档明确 V5 才做 RAG / Memory Pro, so that 团队不会把高级 RAG 工作误塞进 V4。
65. As an API 测试 Agent 用户, I want V4 保持 HTTP API 测试边界, so that 项目不会跑偏到 UI 自动化或通用 Agent。

## Implementation Decisions

- V4 使用一个最高层应用 seam：`DemoRunApplicationService`。它负责接收 demo run 请求，编排已有 Manual Suite Agent Harness，返回前端可消费的 demo result。
- V4 不新建第二套 Agent 编排。Manual Suite Agent Harness 继续承载 V3 智能链路演示；Demo Run API 只是把它产品化、结构化和前端化。
- 真实 LLM 接入必须通过现有 `LlmProvider` seam。不得让 Controller、Demo Console 或 Harness 直接调用第三方模型。
- fake provider 仍是默认 provider。真实 LLM 只能通过显式配置和显式运行 profile 启用。
- 真实 LLM adapter 必须复用现有 LLM 调用审计、脱敏、策略判断、错误分类、timeout 和 token usage 结构。
- Demo Run API 的输入包含 fixture id、provider mode、run profile、是否启用 comparison、是否允许 memory write、输出偏好。
- Demo Run API 的输出是稳定 view model，而不是直接暴露内部领域对象。输出至少包含 run summary、provider summary、plan summary、context summary、tool call summary、suite draft summary、execution summary、variable audit summary、failure analysis summary、memory feedback summary、evaluation comparison summary、artifact references。
- Demo Run result 需要有 schema version，方便后续 V5 增加 RAG / Memory Pro 字段时保持兼容。
- Demo Console 使用轻量本地前端。它优先服务面试演示和手动验证，不承担完整产品后台职责。
- Demo Console 第一屏就是可运行体验：fixture 选择、provider mode 选择、run 按钮、运行状态、结果区域。
- Demo Console 页面按 Agent 设计故事组织：Plan、Context、Tools、Suite、Execution、Failure Analysis、Memory Feedback、Evaluation。
- Demo Console 必须标记 fake、real、comparison 三种模式，避免用户误解输出来源。
- Demo Console 不展示原始 secret、token、Authorization header、API key 或真实模型 key。
- LLM / Fake comparison 以 fake baseline 为稳定参考，再展示 real LLM run 的差异。
- Comparison report 只比较行为差异，不宣称真实 LLM 必然优于 fake。
- V4 支持 Markdown 和 JSON artifact 输出。UI 只是消费同一份结构化结果。
- V4 允许新增有限的 Web/API 入口，但这些入口只属于 demo console，不扩展成完整业务后台。
- V4 不改变 V1 HTTP API 测试边界，不引入 UI 自动化、浏览器自动化、Service 直调测试或 DB 直连断言。
- V4 不把 mem0 或 VikingDB 作为依赖。自研 Memory Engine 和 Context Engine 的完整实现进入 V5 系列。
- V4 不实现真实 embedding、BM25、Query Rewrite、多路召回、RRF、Cross-Encoder rerank 或 Small-to-Big。
- V4 需要为 V5 留出字段和扩展点，例如 context source、citation、retrieval channel、memory evidence、conflict marker，但不要求实现 V5 的高级召回逻辑。
- V4 需要继续保留命令行或测试调用路径。没有浏览器时，用户仍然可以通过 harness 或 API 获得 JSON / Markdown artifact。
- 真实 LLM 的配置错误必须是用户可理解的错误，不允许表现为空结果或空报告。
- 真实 LLM 的结构化输出解析失败必须进入 demo result，而不是让整个应用静默失败。
- 真实 LLM 模式默认不写入长期记忆，除非 run profile 明确允许。comparison 模式不得污染长期记忆。
- 任何 memory feedback 写入都必须保留来源证据、脱敏结果和 provider mode。
- Demo Run API 需要返回足够的 audit summary，用于说明 Agent 的安全和可控性。
- V4 需要在 README 或 Demo 文档中说明本地运行方式、fake 模式、real 模式配置、comparison 模式、报告位置和已知边界。

## Testing Decisions

- 测试应覆盖外部行为，而不是内部 helper。最高测试 seam 是 `DemoRunApplicationService`。
- `DemoRunApplicationService` 测试应断言：给定 fixture 和 fake provider，能返回 completed demo result，并包含 plan、context、tools、suite、execution、failure、memory、evaluation 这些核心区块。
- `DemoRunApplicationService` 测试应断言：real LLM 未配置时，返回清晰的 rejected / failed 状态和配置错误原因。
- `DemoRunApplicationService` 测试应断言：comparison 模式会产生 fake baseline 与 real run 的对比结构，但默认不污染长期记忆。
- `DemoRunApplicationService` 测试应断言：所有输出中的 secret、token 和敏感 header 被脱敏。
- 真实 LLM adapter 测试应通过 fake HTTP server 或 fake client adapter 验证请求构造、响应解析、错误分类、超时和 token usage，不调用真实外部模型。
- LLM provider contract 测试应继续保证 fake provider 和 real adapter 满足同一接口行为。
- Demo Run API 测试应使用 MVC 层或等价入口验证请求/响应 schema、错误码、provider mode 和脱敏输出。
- Demo Console 测试应至少覆盖静态页面可加载、核心控件存在、调用 Demo API 后能渲染主要状态区块。
- Manual Suite Agent Harness 既有测试是 V4 的重要 prior art，应继续作为后端演示链路的回归保护。
- V3-6 manual suite agent verification 是 V4 的验收先例：V4 不应削弱 generated-suite-draft、variable-audit、failure-analysis、memory-feedback、evaluation-comparison 的报告能力。
- Acceptance boundary guard 应明确：V4 可以新增 demo console 和 demo API，但不得引入生产级前端后台、真实外部 HTTP 默认执行、CI 真实 LLM 依赖、mem0/VikingDB 直接依赖或高级 RAG Pro。
- 完整 `mvn test` 应继续在无真实 LLM key 的环境下通过。
- 可选手动验证命令应覆盖 real LLM profile，但不进入默认测试套件。

## Out of Scope

- 不做完整商业化前端。
- 不做用户登录、团队权限、租户隔离或生产级 RBAC。
- 不做完整测试管理平台。
- 不做 UI 自动化、浏览器自动化、移动端测试或 Service 直调测试。
- 不让默认 CI 依赖真实 LLM、真实 embedding 或真实外部业务接口。
- 不接入 mem0 SDK。
- 不接入 VikingDB。
- 不实现 V5 的自研 Memory Engine 完整版。
- 不实现 V5 的自研 Context Engine 完整版。
- 不实现真实 embedding 入库和 pgvector 生产级检索。
- 不实现 BM25、PG full-text、Query Rewrite、多路召回、RRF、Cross-Encoder rerank、LLM rerank。
- 不实现 Small-to-Big 父子索引。
- 不实现 RAG 反馈调权仪表盘。
- 不实现生产级 Memory 治理后台。
- 不做云端部署、监控告警、计费系统或多环境发布系统。

## Further Notes

V4 是 ProbeFlow 从“后端 Agent 能力闭环”走向“可手动体验、可面试展示”的阶段。它的价值不在于一次性补齐所有高级 RAG / Memory，而在于把已有 Agent 设计讲清楚、跑出来、看得见。

V4 完成后，用户在面试中可以按这个顺序讲：

```text
1. 我有一个受控 Planner，不是裸 ReAct。
2. Planner 只能通过 Tool Contract 调用允许的工具。
3. 每一步都有 Policy Validator 和审计。
4. Agent 使用 Unified Context，不是简单拼 prompt。
5. SUITE 执行有 ExecutionContext 和变量审计。
6. 链路失败后能做 root cause 和 downstream impact 分析。
7. 失败经验能进入 Memory Feedback。
8. 我可以用 fake baseline 保证可测，也可以用 real LLM 做手动实验。
9. Demo Console 能把这些设计一次性展示出来。
10. 后续 V5 会继续把自研 Memory / RAG / Context 做成 Pro 版本。
```

V5 的方向已经明确：

- 真实 embedding + pgvector 检索。
- 自研 Memory fact extraction + dedup。
- 自研 Memory entity graph。
- RAG Query Rewrite + 多路召回。
- Rerank + Small-to-Big。
- 自研 Context Engine 强化。

这些内容是 V4 的后续路线，不进入本 PRD 的实现范围。
