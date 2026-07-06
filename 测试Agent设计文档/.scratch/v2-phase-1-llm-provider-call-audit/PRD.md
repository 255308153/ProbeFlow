状态：ready-for-agent

# V2 Phase 1：LLM Provider 与调用审计 PRD

## Problem Statement

V1 已经完成 ProbeFlow 的确定性后端闭环。系统可以在不依赖真实 LLM Planner 的情况下完成：

```text
Task 初始化
-> API 分析
-> RAG / Memory 上下文构建
-> 测试用例生成
-> HTTP 执行
-> 失败分析
-> 报告生成
-> 任务编排
```

这说明 ProbeFlow 已经具备稳定的“身体”和“基础反射”。但如果要把 ProbeFlow 从确定性测试流水线升级成真正的垂直 API 测试 Agent，就必须接入 LLM。

问题是：LLM 不能直接接进主流程，更不能一开始就让它控制工具调用。

如果没有统一 LLM Provider 与调用审计层，后续 Agent Core 会遇到几个严重问题：

- 各模块可能直接调用不同模型 SDK，导致调用方式分散。
- Prompt 无版本、无用途、无输入输出约束，难以回溯。
- 真实 LLM 调用依赖网络和 API key，CI 不稳定。
- 无法统计 token、耗时、错误类型和模型成本。
- LLM 输出不可追踪，后续 Planner 决策无法审计。
- 失败时无法区分是网络错误、模型错误、解析错误还是策略拒绝。
- 面试中无法讲清楚“如何工程化接入 LLM，而不是随手写 prompt 调接口”。

V2 Phase 1 的核心问题是：

```text
如何把 LLM 安全、可替换、可测试、可审计地接入 ProbeFlow，而不破坏 V1 的确定性闭环？
```

因此，本阶段只做 LLM 基础设施，不做 Controlled Planner，不做 Agent Loop，不让 LLM 调工具，不让 LLM 修改业务数据。

## Solution

实现 V2 Phase 1 的 LLM Provider 与调用审计系统，以 `LlmApplicationService` 作为最高层应用服务 seam。

核心流程：

```text
LlmCallRequest
-> resolve PromptTemplate
-> render prompt variables
-> select LlmProvider
-> apply LlmExecutionOptions / LlmPolicy
-> execute provider call
-> normalize response or error
-> persist LlmCallLog
-> return LlmCallResult
```

本阶段要建立一套统一 LLM 调用协议：

- `LlmApplicationService`：统一入口，负责调用编排、模板渲染、provider 选择、调用日志、错误归一化。
- `LlmProvider`：模型供应商抽象，后续可接 OpenAI、Claude、本地模型等。
- `FakeLlmProvider`：确定性 fake provider，用于 CI、单元测试、集成测试。
- `PromptTemplateRegistry`：管理 prompt 模板、用途、版本、输入变量和输出约束。
- `LlmCallLog`：持久化每次调用的审计信息。
- `LlmExecutionOptions`：调用参数，例如 provider、model、temperature、maxTokens、timeout、retry。
- `LlmPolicy`：控制是否允许真实 provider、测试环境是否禁止真实网络、是否必须使用 fake provider。

本阶段的 LLM 只能作为“被调用的模型基础设施”，不能作为 Agent 决策者。它可以被未来 Planner、失败解释、报告润色、上下文缺口提问等模块使用，但 V2 Phase 1 本身不实现这些高级能力。

推荐测试 seam：

- 最高层 seam：`LlmApplicationService`
- 支撑 seam：`LlmProvider`
- 支撑 seam：`PromptTemplateRegistry`
- 持久化 seam：`LlmCallLogRepository`

测试应优先通过 `LlmApplicationService` 验证外部行为：

- 使用 fake provider 返回确定性响应。
- 调用成功后写入 `LlmCallLog`。
- 调用失败后写入失败日志。
- prompt 变量正确渲染。
- provider/model/options 正确记录。
- token、耗时、错误类型被归一化。
- test profile 下不会调用真实 LLM 网络。

## User Stories

1. 作为 Agent 系统开发者，我希望有统一的 LLM 调用入口，以便后续 Planner、失败解释和报告润色都通过同一套协议调用模型。
2. 作为 Agent 系统开发者，我希望 LLM Provider 可以替换，以便未来支持 OpenAI、Claude、本地模型或其他供应商。
3. 作为 Agent 系统开发者，我希望有 Fake LLM Provider，以便 CI 和自动化测试不依赖真实网络和 API key。
4. 作为 Agent 系统开发者，我希望 LLM 请求和响应是结构化对象，以便不同模块不用直接依赖第三方 SDK。
5. 作为 Agent 系统开发者，我希望 prompt 模板有用途标识，以便区分 planner、failure insight、report narrative 等不同场景。
6. 作为 Agent 系统开发者，我希望 prompt 模板有版本号，以便后续模型行为变化可以追踪到具体模板版本。
7. 作为 Agent 系统开发者，我希望 prompt 模板可以声明输入变量，以便调用前发现缺失变量。
8. 作为 Agent 系统开发者，我希望 prompt 模板可以声明输出格式要求，以便后续结构化解析更稳定。
9. 作为 Agent 系统开发者，我希望调用时可以传入 temperature 和 max tokens，以便不同任务使用不同生成策略。
10. 作为 Agent 系统开发者，我希望调用时可以指定 provider 和 model，以便在不同环境切换模型。
11. 作为 Agent 系统开发者，我希望调用时可以设置 timeout，以便模型调用不会无限阻塞任务。
12. 作为 Agent 系统开发者，我希望调用时可以设置 retry 策略，以便临时网络或限流错误可以被有限重试。
13. 作为 Agent 系统开发者，我希望所有 LLM 调用都记录耗时，以便定位慢调用和后续成本优化。
14. 作为 Agent 系统开发者，我希望所有 LLM 调用都记录 token 用量，以便估算成本和上下文预算。
15. 作为 Agent 系统开发者，我希望所有 LLM 调用都记录 provider 和 model，以便审计不同模型的效果。
16. 作为 Agent 系统开发者，我希望所有 LLM 调用都记录 prompt template id 和版本，以便回放和定位 prompt 变更影响。
17. 作为 Agent 系统开发者，我希望所有 LLM 调用都记录 request hash，以便识别重复调用和未来做缓存。
18. 作为 Agent 系统开发者，我希望成功调用保存响应摘要，以便后续 Planner 决策可以被审计。
19. 作为 Agent 系统开发者，我希望失败调用保存错误分类，以便区分 provider 错误、网络错误、超时、限流和解析错误。
20. 作为 Agent 系统开发者，我希望失败调用也写入 LlmCallLog，以便错误不是黑盒。
21. 作为 Agent 系统开发者，我希望 Fake Provider 可以按模板或测试输入返回固定响应，以便测试可以稳定断言。
22. 作为 Agent 系统开发者，我希望 Fake Provider 可以模拟错误，以便测试 timeout、provider error 和 parse error。
23. 作为 Agent 系统开发者，我希望 test profile 默认禁止真实 LLM 调用，以便 CI 不依赖外部服务。
24. 作为 Agent 系统开发者，我希望真实 provider 被配置显式启用，以便本地或生产环境不会误触发真实调用。
25. 作为 Agent 系统开发者，我希望调用结果能标识是否来自 fake provider，以便测试和审计区分真实输出。
26. 作为 Agent 系统开发者，我希望 LLM 调用可以和 Task id 关联，以便后续查看某个任务中发生过哪些模型调用。
27. 作为 Agent 系统开发者，我希望 LLM 调用可以和 PlanStep id 关联，以便未来 Planner 和重规划决策可以追踪来源。
28. 作为 Agent 系统开发者，我希望 LLM 调用可以和调用用途关联，以便统计 planner、报告润色、失败解释的调用分布。
29. 作为 Agent 系统开发者，我希望 LLM 调用结果不直接修改业务对象，以便模型输出不会绕过策略校验。
30. 作为 Agent 系统开发者，我希望 LLM 调用入口不暴露工具执行能力，以便模型不能直接调 HTTP 执行器或数据库。
31. 作为 Agent 系统开发者，我希望 LLM Provider 返回归一化错误，以便上层不需要理解各厂商 SDK 异常。
32. 作为 Agent 系统开发者，我希望 LLM Provider 返回归一化 token usage，以便不同供应商统计方式可以统一。
33. 作为 Agent 系统开发者，我希望 LLM Provider 返回原始 provider trace id 或 request id，以便排查供应商侧问题。
34. 作为 Agent 系统开发者，我希望 prompt 渲染失败时不调用 provider，以便缺失变量不会产生无意义模型请求。
35. 作为 Agent 系统开发者，我希望 prompt 渲染失败也能形成明确错误结果，以便调用方知道如何修复输入。
36. 作为 Agent 系统开发者，我希望调用日志避免保存敏感 header 或 API key，以便审计不泄露秘密。
37. 作为 Agent 系统开发者，我希望调用日志可以裁剪超长 prompt 和 response，以便数据库不会被大文本撑爆。
38. 作为 Agent 系统开发者，我希望完整 prompt 可以通过 hash 或可配置字段控制保存，以便在调试和隐私之间取舍。
39. 作为 Agent 系统开发者，我希望 LLM 模块不依赖 orchestration 模块的内部实现，以便 LLM 基础设施可以被多个模块复用。
40. 作为 Agent 系统开发者，我希望 LLM 模块不依赖真实 Planner，以便本阶段可以独立交付。
41. 作为 Agent 系统开发者，我希望 LLM 调用审计表有迁移脚本，以便数据结构可以在本地和 CI 环境一致。
42. 作为 Agent 系统开发者，我希望 LLM 调用日志可以按状态查询，以便定位失败调用。
43. 作为 Agent 系统开发者，我希望 LLM 调用日志可以按 task id 查询，以便未来任务详情能展示模型调用轨迹。
44. 作为 Agent 系统开发者，我希望 LLM 调用日志可以按用途查询，以便未来评估不同 Agent 能力的模型使用情况。
45. 作为 Agent 系统开发者，我希望 LLM 调用结果有稳定状态枚举，以便上层可以统一处理 SUCCESS、FAILED、BLOCKED、SKIPPED。
46. 作为 Agent 系统开发者，我希望 LLM 调用在 policy 拒绝时不会访问 provider，以便安全策略优先于模型调用。
47. 作为 Agent 系统开发者，我希望 LLM 调用 policy 可以表达“只允许 fake provider”，以便测试环境绝对安全。
48. 作为 Agent 系统开发者，我希望 LLM 调用 policy 可以表达“允许真实 provider 但必须显式配置”，以便生产环境可控启用。
49. 作为 Agent 系统开发者，我希望边界测试证明本阶段没有引入 Agent Loop，以便 V2 Phase 1 不越界。
50. 作为 Agent 系统开发者，我希望边界测试证明本阶段没有让 LLM 调工具，以便后续 Planner 仍然受控。
51. 作为 Agent 系统开发者，我希望边界测试证明本阶段没有修改 TestCase、ExecutionRecord、Observation、Memory 和 Report 的业务写路径，以便 LLM 基础设施不污染 V1 闭环。
52. 作为 Agent 系统开发者，我希望边界测试证明 CI 不需要真实 LLM API key，以便项目开源或面试演示时可稳定运行。
53. 作为面试候选人，我希望能讲清楚为什么先做 LLM Provider 抽象，以便体现工程化 Agent 设计思路。
54. 作为面试候选人，我希望能讲清楚 fake provider 的作用，以便说明如何测试非确定性的 LLM 系统。
55. 作为面试候选人，我希望能讲清楚调用审计的价值，以便说明 Agent 决策可以追踪和复盘。
56. 作为面试候选人，我希望能讲清楚为什么本阶段不做 Planner，以便说明系统遵循“先安全接入，再受控决策”的演进路线。

## Implementation Decisions

- 新增 LLM 基础设施模块，作为 V2 Agent Core 的第一层能力。
- 最高层入口使用 `LlmApplicationService` 或等价应用服务。
- 定义 `LlmProvider` 抽象，屏蔽不同模型供应商 SDK 差异。
- 至少实现 `FakeLlmProvider`，用于测试和 CI。
- 真实 provider 可以做接口预留或最小可关闭实现，但不能成为 CI 必需依赖。
- 定义 `LlmRequest`、`LlmResponse`、`LlmCallResult`、`LlmExecutionOptions` 等结构化对象。
- 定义 LLM 调用状态枚举，例如 SUCCESS、FAILED、BLOCKED、SKIPPED。
- 定义 LLM 错误分类，例如 TIMEOUT、RATE_LIMITED、PROVIDER_ERROR、NETWORK_ERROR、TEMPLATE_RENDER_ERROR、POLICY_BLOCKED、OUTPUT_PARSE_ERROR。
- 添加 prompt template registry，用于管理模板用途、版本、变量和输出约束。
- Prompt 模板先以内置 registry 或配置类实现即可，不要求数据库化模板管理。
- 添加 LLM 调用日志持久化模型和 repository。
- LLM 调用日志记录 task id、plan step id、purpose、provider、model、template id、template version、request hash、status、error type、latency、token usage、created at。
- LLM 调用日志可以保存裁剪后的 prompt 和 response 摘要，但不得保存 API key 或敏感 header。
- 请求 hash 使用稳定算法生成，用于未来缓存、去重和审计。
- Provider 调用必须经过 policy 检查，policy 拒绝时不能访问真实 provider。
- test profile 默认只允许 fake provider。
- LLM 调用不允许直接修改 TestCase、ExecutionRecord、Observation、Memory、Report 或 Task 状态。
- LLM 调用不允许直接调用 ToolRouter、PlanStepRunner、HTTP 执行器或数据库业务写服务。
- 本阶段不实现 Controlled Planner、Policy Validator、Replanning Loop 或 Human-in-the-loop。
- 本阶段只提供后续 Agent 能力可复用的 LLM 基础设施。
- 保持 Java/Spring 为核心平台，不引入 Python 重写。
- 不引入前端、REST Controller、外部通知、ticket、队列、worker 或真实网络依赖的 CI 流程。

## Testing Decisions

- 测试应优先通过 `LlmApplicationService` 验证外部行为，不测试私有 helper。
- 使用 `FakeLlmProvider` 验证成功调用、失败调用、错误模拟和 token usage。
- 测试 prompt template 变量渲染成功路径。
- 测试 prompt template 缺少变量时不调用 provider，并返回 TEMPLATE_RENDER_ERROR。
- 测试调用成功后持久化 `LlmCallLog`。
- 测试调用失败后仍然持久化失败日志。
- 测试 policy 拒绝时不访问 provider，并记录 POLICY_BLOCKED。
- 测试 test profile 下真实 provider 默认不可用。
- 测试 provider/model/options/purpose/template version/request hash 被正确记录。
- 测试 latency 和 token usage 被归一化记录。
- 测试超长 prompt/response 摘要裁剪，避免日志字段无限增长。
- 测试敏感配置不会进入调用日志。
- 测试 LLM 模块不会创建或修改 TestCase、ExecutionRecord、Observation、Memory、Report。
- 添加 V2 Phase 1 边界测试，证明没有引入 Controlled Planner、Agent Loop、ToolRouter 直接调用、真实 LLM CI 依赖、前端、REST Controller、队列、worker、外部通知或 ticket 集成。
- 参考现有 Phase 9 orchestration 测试风格，保持应用服务测试为主。
- 参考现有 boundary guard tests，新增 V2 Phase 1 acceptance boundary guard。
- 完整后端验证命令仍为 `mvn test`。

## Out of Scope

- Controlled Planner。
- Replanning Loop。
- Agent Loop。
- Tool Contract。
- Tool Router 增强。
- Policy Validator 完整实现。
- Human-in-the-loop workflow。
- Agent Evaluation。
- LLM 直接生成 PlanStep 并执行。
- LLM 直接调用 HTTP 执行器。
- LLM 直接修改 TestCase、ExecutionRecord、Observation、Memory、Report。
- 真实 LLM 成为 CI 必需依赖。
- 前端 UI。
- REST Controller。
- 外部通知。
- GitHub、Jira、Slack 集成。
- 队列或后台 worker。
- Python/pytest runner。
- embedding/rerank provider。
- prompt 在线编辑后台。
- 多租户、权限、团队协作。

## Further Notes

- V2 Phase 1 是 Agent Core 的地基，不是 Agent Core 的全部。
- 本阶段最重要的设计价值是“把 LLM 当成可审计基础设施”，而不是把 LLM 当成不可控黑盒。
- 后续 V2 Phase 2 可以在此基础上设计 Tool Contract 与 Agent Policy。
- 后续 V2 Phase 3 可以在此基础上设计 Controlled Planner。
- 面试讲解时可以强调：ProbeFlow 先用 V1 做确定性闭环，再用 V2 Phase 1 安全接入 LLM Provider，之后才逐步进入受控 Planner 和重规划循环。
- 现有未跟踪的 `prd-publication-workflow` scratch 目录与本 PRD 无关，不应修改。
