状态：ready-for-agent

# ProbeFlow V2 Phase 6：Human-in-the-loop Agent Workflow PRD

## Problem Statement

ProbeFlow V2 已经完成了 LLM Provider、Tool Contract / AgentPolicy、Controlled Planner、Policy Validator，以及 Replanning Loop。系统现在可以在明确异常场景下进入重规划，并且在需要人类输入时返回 `WAITING_FOR_HUMAN` / `WAITING_FOR_REVIEW` 这类暂停状态。

但目前“人类介入”还不是一个完整的 Agent 工作流。

当前系统已经有一些零散能力：

- 用例草稿可以进入 manual review。
- `ManualReviewGate` 可以判断草稿是否还有 pending review。
- Replanning Loop 可以把 `requiredHumanInput` 写入任务 metadata。
- `WAIT_FOR_HUMAN` 可以暂停任务。
- Review completed trigger 可以让 Replanning Loop 尝试恢复任务。

这些能力证明系统可以暂停，但还没有把“人类输入”建模成 Agent 状态机的一等公民：

- Agent 向人类提出的问题没有统一的 request lifecycle。
- 人类的采纳、拒绝、补充输入、修改建议没有统一 decision record。
- Draft review、blocker resolution、高风险执行确认、Planner 澄清问题还没有同一套协议。
- 人类拒绝原因没有进入后续 PlannerInput。
- 人类反馈还没有被整理成可进入 MemoryCandidate 的结构化材料。
- 等待状态主要靠 Task metadata 表达，不够可审计、可恢复、可测试。

从面试讲解角度，Phase 6 要回答一个关键问题：

```text
Agent 什么时候应该停下来问人？
人类回答后，Agent 如何带着这个反馈继续？
```

这不能只靠 UI 按钮或临时 metadata。Phase 6 要把 Human-in-the-loop 变成后端 Agent Core 的正式领域模块。

## Solution

新增 Human-in-the-loop Agent Workflow，建立统一的人类请求、决策记录、反馈消费和恢复入口。

目标链路是：

```text
PolicyValidator / ReplanningLoop / ManualReviewGate
        |
        v
HumanReviewRequest
        |
        v
HumanDecisionRecord
        |
        v
HumanFeedbackApplicationService
        |
        v
ReplanningTrigger.REVIEW_COMPLETED / HUMAN_INPUT_REQUIRED
        |
        v
PlannerInput + user feedback context
        |
        v
ReplanningApplicationService
        |
        v
Task continues / remains waiting / safely stops
```

Phase 6 的核心不是做前端 UI，而是把后端工作流做实：

- Agent 可以创建明确的人类请求。
- 每个请求有类型、状态、等待原因、required input schema、风险等级、关联 task / step / planner decision / policy result。
- 人类可以提交结构化决策：approve、reject、provide input、promote draft、discard draft、request changes、resolve blocker。
- 决策必须被记录为不可随意覆盖的 decision record。
- 决策消费后可以恢复 Replanning Loop 或保持等待。
- 用户反馈可以生成 MemoryCandidate 输入，为 Phase 7 的 Memory Feedback Loop 做准备。
- 所有逻辑在后端应用层可测试，不依赖前端 UI 或真实 LLM。

建议的最高测试接缝是 `HumanInTheLoopApplicationService` 或等价应用层服务。测试应构造 Task、PlanStep、HumanReviewRequest、HumanDecisionRecord、TestCaseDraft 和 ReplanningResult，断言请求状态、任务状态、草稿状态、恢复触发、decision audit、memory candidate 输出，而不是测试 Controller 或 UI。

## User Stories

1. As an Agent 系统开发者, I want a HumanReviewRequest model, so that Agent 对人类的请求有统一生命周期。
2. As an Agent 系统开发者, I want a HumanDecisionRecord model, so that 人类的回答、采纳和拒绝可以被审计。
3. As an Agent 系统开发者, I want request status values, so that 人类请求可以表达 pending、answered、rejected、expired、cancelled、consumed。
4. As an Agent 系统开发者, I want request type values, so that draft review、blocker resolution、high-risk approval、missing input、planner clarification 可以统一处理。
5. As an Agent 系统开发者, I want waiting reason stored with each request, so that 用户知道 Agent 为什么停下来。
6. As an Agent 系统开发者, I want required input schema stored with each request, so that 人类输入可以结构化验证。
7. As an Agent 系统开发者, I want each request linked to task id, so that 可以从任务追踪所有等待点。
8. As an Agent 系统开发者, I want each request optionally linked to plan step id, so that 可以定位哪个步骤触发了等待。
9. As an Agent 系统开发者, I want each request optionally linked to planner decision id, so that 可以回溯 Planner 为什么问人。
10. As an Agent 系统开发者, I want each request optionally linked to policy validation result, so that 可以解释为什么需要人工确认。
11. As an Agent 系统开发者, I want request metadata to include risk level, so that 高风险确认和普通补充输入可以区分。
12. As an Agent 系统开发者, I want request metadata to include source trigger, so that 能知道请求来自 Replanning、Policy、ManualReviewGate 还是 Planner。
13. As an Agent 系统开发者, I want active request lookup by task, so that 编排层能知道任务是否还在等待人类。
14. As an Agent 系统开发者, I want duplicate pending requests to be deduplicated, so that 同一个 blocker 不会反复创建多条请求。
15. As an Agent 系统开发者, I want request expiration support, so that 长时间无人处理的请求可以安全阻塞或取消。
16. As an Agent 系统开发者, I want request cancellation support, so that task cancelled 后 pending human requests 不会继续有效。
17. As an Agent 系统开发者, I want decision type values, so that approve、reject、provide input、request changes、promote draft、discard draft、resolve blocker 可以统一记录。
18. As an Agent 系统开发者, I want decision actor recorded, so that 可以审计是谁做了决定。
19. As an Agent 系统开发者, I want decision reason recorded, so that 用户拒绝或修改 Agent 建议时原因不丢失。
20. As an Agent 系统开发者, I want decision payload stored as structured data, so that 人类补充的环境变量、参数、case 修改建议可以被后续流程消费。
21. As an Agent 系统开发者, I want decision timestamps, so that 等待时长和恢复顺序可以分析。
22. As an Agent 系统开发者, I want immutable decision history, so that 人类决策不会被后续覆盖。
23. As an Agent 系统开发者, I want request status to change when decision is accepted, so that 待处理列表不会保留已消费请求。
24. As an Agent 系统开发者, I want invalid decisions rejected, so that 不符合 request schema 的人类输入不会污染任务。
25. As an Agent 系统开发者, I want draft review request creation, so that test case draft review 不再只是 Task metadata 阻塞。
26. As an Agent 系统开发者, I want pending draft ids attached to draft review request, so that 人类知道需要 review 哪些草稿。
27. As an Agent 系统开发者, I want promote draft decision support, so that 人类可以选择哪些 draft 进入正式 test case。
28. As an Agent 系统开发者, I want discard draft decision support, so that 人类可以明确丢弃不合适的 draft。
29. As an Agent 系统开发者, I want request changes decision support, so that 人类可以要求 Agent 修改用例而不是只二选一。
30. As an Agent 系统开发者, I want draft review feedback captured, so that 用户为什么采纳或拒绝用例可以进入后续上下文。
31. As an Agent 系统开发者, I want draft review completion to trigger recovery, so that review 完成后 Agent 可以继续执行计划。
32. As an Agent 系统开发者, I want ManualReviewGate to remain compatible, so that V1 半自动 review 行为不被破坏。
33. As an Agent 系统开发者, I want blocker resolution request creation, so that readiness 不足或上下文缺失可以变成明确的人类任务。
34. As an Agent 系统开发者, I want human-provided blocker resolution input, so that Agent 可以拿到缺少的 baseUrl、token、环境、参数或说明。
35. As an Agent 系统开发者, I want blocker resolution to update task metadata or equivalent context, so that Replanning 可以消费已解决信息。
36. As an Agent 系统开发者, I want unresolved blockers to keep task waiting, so that Agent 不会在缺信息时强行继续。
37. As an Agent 系统开发者, I want high-risk approval request creation, so that 危险执行或高风险失败处理必须有人确认。
38. As an Agent 系统开发者, I want approval decision to resume recovery, so that 人类确认后 Agent 可以继续安全路径。
39. As an Agent 系统开发者, I want rejection decision to stop or replan safely, so that 人类拒绝后 Agent 不会继续原危险动作。
40. As an Agent 系统开发者, I want user rejection reason to enter PlannerInput, so that Planner 知道人类为什么拒绝。
41. As an Agent 系统开发者, I want planner clarification requests, so that Planner 不确定时可以问一个结构化问题。
42. As an Agent 系统开发者, I want clarification answers to resume Replanning, so that Agent 可以带着答案继续。
43. As an Agent 系统开发者, I want human input to be sanitized before audit, so that token、secret、cookie 不会直接进入日志。
44. As an Agent 系统开发者, I want secret-like fields to be masked, so that 人类补充的敏感信息不会泄露。
45. As an Agent 系统开发者, I want request and decision summaries, so that 后续控制台可以展示但本阶段不需要 UI。
46. As an Agent 系统开发者, I want request and decision records to be queryable by task, so that 可以生成任务时间线。
47. As an Agent 系统开发者, I want request and decision records to be queryable by status, so that 可以找到所有 pending human work。
48. As an Agent 系统开发者, I want request and decision records to be queryable by type, so that 可以分析 Agent 常问哪类问题。
49. As an Agent 系统开发者, I want Replanning Loop to create human requests from WAITING_FOR_HUMAN results, so that Phase 5 的等待状态升级为正式 HITL 请求。
50. As an Agent 系统开发者, I want HumanInTheLoop workflow to call Replanning when decision is consumed, so that 人类反馈后任务可以恢复。
51. As an Agent 系统开发者, I want review completed trigger to include decision context, so that Planner 可以看到人类选择和拒绝原因。
52. As an Agent 系统开发者, I want human decisions to become memory candidates, so that Phase 7 可以从人类反馈中学习。
53. As an Agent 系统开发者, I want promoted draft feedback to become a memory candidate, so that Agent 未来知道什么样的用例更有价值。
54. As an Agent 系统开发者, I want discarded draft feedback to become a memory candidate, so that Agent 未来避免类似低质量用例。
55. As an Agent 系统开发者, I want blocker resolution feedback to become a memory candidate, so that Agent 未来知道某类环境缺口如何解决。
56. As an Agent 系统开发者, I want high-risk rejection feedback to become a memory candidate, so that Agent 未来更谨慎地处理类似风险。
57. As an Agent 系统开发者, I want memory candidate creation to be auditable, so that 不是所有用户话语都会直接写入长期记忆。
58. As an Agent 系统开发者, I want Phase 6 not to auto-write long-term memory without review, so that 长期记忆仍由 Phase 7 的反馈循环治理。
59. As an Agent 系统开发者, I want human request creation to be idempotent, so that 重试 Replanning 不会产生重复 pending request。
60. As an Agent 系统开发者, I want decision consumption to be idempotent, so that 重复提交不会重复恢复计划。
61. As an Agent 系统开发者, I want stale decision submission rejected, so that 已取消或已消费请求不会被再次处理。
62. As an Agent 系统开发者, I want completed tasks not to accept new human requests, so that 历史任务不会被重新打开。
63. As an Agent 系统开发者, I want cancelled tasks to cancel pending requests, so that 用户取消任务后不会残留待办。
64. As an Agent 系统开发者, I want transactional request and decision handling, so that request、decision、task status、replanning trigger 不会半更新。
65. As an Agent 系统开发者, I want Fake workflows for HITL tests, so that CI 不依赖真实 UI 或真实 LLM。
66. As an Agent 系统开发者, I want no REST Controller in Phase 6, so that 本阶段聚焦后端 Agent Core。
67. As an Agent 系统开发者, I want no Web Console in Phase 6, so that UI 留到 V3 产品化。
68. As an Agent 系统开发者, I want no complex permission system in Phase 6, so that actor 记录足以支撑本阶段审计。
69. As an Agent 系统开发者, I want no multi-user approval workflow in Phase 6, so that 企业审批留到后续版本。
70. As an Agent 系统开发者, I want Phase 6 to be explainable in interviews, so that 可以讲清楚可信 Agent 为什么必须有人类介入点。

## Implementation Decisions

- 新增 Human-in-the-loop 后端领域模块，负责 human request、human decision、feedback consumption 和 recovery handoff。
- 建议的最高应用层入口是 HumanInTheLoopApplicationService 或等价服务。
- 新增 HumanReviewRequest 或等价模型，表示 Agent 对人类发出的等待请求。
- 新增 HumanDecisionRecord 或等价模型，表示人类对请求的结构化响应。
- HumanReviewRequest 应包含 task id、可选 plan step id、request type、status、waiting reason、required input schema、risk level、source trigger、planner decision id、policy reason、metadata、created/updated/expired timestamps。
- HumanDecisionRecord 应包含 request id、task id、decision type、actor、reason、payload、sanitized payload summary、created timestamp。
- Request type 至少覆盖 draft review、blocker resolution、high-risk approval、planner clarification、missing input、review completion。
- Decision type 至少覆盖 approve、reject、provide input、request changes、promote draft、discard draft、resolve blocker。
- Request status 至少覆盖 pending、answered、consumed、rejected、cancelled、expired。
- Human request 创建必须幂等，避免同一个 task / trigger / source step / blocker 反复创建 pending request。
- Human decision 提交必须校验 request 是否仍 pending。
- Human decision payload 必须按 required input schema 或等价规则验证。
- Human decision payload 必须做敏感字段 masking / sanitization 后才能进入审计摘要。
- Draft review 继续复用 TestCaseDraft、DraftStatus 和 ManualReviewGate 的现有行为。
- Phase 6 可以增强 draft review 的记录和恢复流程，但不应破坏 V1 manual promotion 模式。
- Draft promote / discard / request changes 应通过 HumanDecisionRecord 表达，并同步更新 draft 状态或任务 metadata。
- Request changes 可以触发 Replanning Loop，而不是直接让 LLM 改数据库。
- Blocker resolution 决策可以把结构化输入写入 task metadata 或等价上下文，供 Replanning 使用。
- High-risk approval 决策可以触发 Replanning Loop 恢复，reject 则返回安全停止或继续等待。
- Planner clarification answer 应进入 ReplanningRequest 的 human input 或 constraints，让 PlannerInput 能看到人类回答。
- Replanning Loop 的 WAITING_FOR_HUMAN 结果应能创建 HumanReviewRequest，而不只写 task metadata。
- Human decision 被消费后，可以触发 `REVIEW_COMPLETED` 或 `HUMAN_INPUT_REQUIRED` 等已有 ReplanningTrigger。
- HumanInTheLoopApplicationService 与 ReplanningApplicationService 的耦合应保持在应用层，不让 entity 直接调用服务。
- 用户反馈应生成 MemoryCandidateRequest 或等价 candidate summary，但不在 Phase 6 中直接完成长期记忆学习闭环。
- Phase 6 不实现复杂权限；actor 字段作为审计信息即可。
- Phase 6 不实现 REST Controller、Web Console、前端页面、多人审批流。
- 如现有 Task metadata 无法支撑审计，应新增轻量持久化表；如果新增表，必须提供 Flyway migration 和 repository。
- 所有 request / decision / task 状态变更应在事务边界内完成。
- 真实 LLM 不是 Phase 6 测试依赖。

## Testing Decisions

- 最高测试接缝是 HumanInTheLoopApplicationService 或等价应用层服务。
- 测试应断言外部行为：request 状态、decision record、Task 状态、TestCaseDraft 状态、ReplanningResult、MemoryCandidate 输出和审计摘要。
- 测试不要断言私有方法、内部 helper 顺序或 UI 行为。
- 需要测试创建 draft review request。
- 需要测试 promote draft decision。
- 需要测试 discard draft decision。
- 需要测试 request changes decision 触发 recovery。
- 需要测试 ManualReviewGate 兼容性。
- 需要测试 blocker resolution request 和 provide input decision。
- 需要测试 high-risk approval request、approve 和 reject。
- 需要测试 planner clarification request 和 answer。
- 需要测试 decision payload schema 校验。
- 需要测试敏感字段 masking。
- 需要测试 pending request 幂等创建。
- 需要测试 decision 幂等消费。
- 需要测试 stale / cancelled / consumed request 拒绝再次提交。
- 需要测试 completed task 不接受新 request。
- 需要测试 cancelled task 取消 pending request。
- 需要测试 human decision 触发 Replanning Loop 的恢复路径。
- 需要测试 human feedback 生成 memory candidate，但不直接验证长期记忆效果。
- 需要新增 Phase 6 acceptance boundary guard，证明本阶段没有新增 REST Controller、Web Console、复杂权限、多用户审批、外部通知、工单系统或真实 CI。
- 完成后应运行后端完整测试。

## Out of Scope

- 不做完整前端 UI。
- 不做 Web Console。
- 不做 REST Controller。
- 不做多人协同编辑。
- 不做复杂权限系统。
- 不做企业审批流。
- 不做 Slack、邮件、Webhook、GitHub issue、Jira 或真实 CI 集成。
- 不做任务通知系统。
- 不做自由 AutoGPT 式 Agent。
- 不让 LLM 直接消费未校验的人类输入并修改数据库。
- 不让人类输入绕过 PolicyValidator。
- 不实现长期 Memory Feedback Loop 的完整学习、合并、置信度更新；那是 Phase 7。
- 不实现 Agent Evaluation Harness；那是 Phase 8。
- 不要求真实 LLM 作为 CI 依赖。
- 不重写 V1 manual review 和 TaskOrchestration 默认流程。

## Further Notes

Phase 6 是 ProbeFlow Agent Core 的可信性阶段。

前面几个阶段已经完成：

- Phase 3：Planner 能提出结构化建议。
- Phase 4：PolicyValidator 能拦截越界建议。
- Phase 5：Replanning Loop 能在异常时受控恢复。

但真正可信的 Agent 不能只会自己决定。它还必须知道：

```text
什么时候该问人，
问什么，
人回答后如何恢复，
人的反馈如何沉淀为未来改进材料。
```

Phase 6 的面试讲解重点可以是：

```text
我没有把 Human-in-the-loop 当成一个 UI 按钮。
我把它建模成 Agent 状态机中的正式领域对象：
HumanReviewRequest 表示 Agent 为什么停下来，
HumanDecisionRecord 表示人类如何回答，
ReplanningTrigger 负责把回答带回 Agent Loop，
MemoryCandidate 为后续学习保留材料。
```

这让 ProbeFlow 从“能失败恢复的 Agent”，进一步变成“能和人类协作的 Agent”。
