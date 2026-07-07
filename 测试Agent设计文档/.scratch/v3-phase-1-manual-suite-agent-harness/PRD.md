状态：ready-for-agent

# ProbeFlow V3-1：Manual Suite Agent Harness 与演示 Fixture PRD

## Problem Statement

ProbeFlow V3 的总目标是把基础版 `SUITE` 升级为智能链路测试 Agent：系统要能理解业务链路、生成变量提取规则、维护执行上下文、执行链路、解释失败并把经验反馈给记忆系统。

但如果一开始就直接实现 Business Flow Discovery、DependencyLinker、ExecutionContext、Suite Failure Analysis 和 Light Console，开发风险会很高：

- 后续 V3-2 到 V3-6 的能力缺少一个统一的手动验证入口。
- 同事实现某个模块后，很难用一条命令看到它是否真的进入完整 Agent 闭环。
- 没有前端时，开发者只能看分散的应用层测试，难以向面试官演示 Agent 的整体行为。
- 真实 LLM 还不能进入默认 CI，系统需要一个稳定的 fake provider / fake HTTP gateway 演示路径。
- V3 的报告产物、变量审计、失败分析摘要和 Memory Feedback 摘要如果不先定义，后续各 phase 容易各自输出一套格式。

V3-1 要解决的不是“立即做完智能链路 Agent”，而是先搭建一个可重复、可演示、可扩展的后端 harness：

```text
固定 fixture
  -> 启动一次受控 V3 suite agent run
  -> 产出 SUITE 草稿摘要
  -> 产出执行结果摘要
  -> 产出变量审计占位或摘要
  -> 产出失败分析摘要
  -> 产出 Memory Feedback 摘要
  -> 产出 JSON / Markdown 报告
```

这样后续每个 phase 都能把自己的真实能力接进同一个入口，而不是散落成一堆不能演示的测试片段。

## Solution

新增 `Manual Suite Agent Harness`，作为 V3 开发和面试演示阶段的后端手动验证入口。

这个 harness 的定位是：

- 面向本地开发、手动测试和面试演示。
- 默认使用 deterministic fake provider 和 fake HTTP gateway。
- 从固定 fixture 加载一组订单、支付、鉴权等典型链路样例。
- 串联现有应用层能力，优先复用 Task、ApiSpec、Knowledge RAG、Memory、TestCase、HttpExecution、FailureAnalysis、Report、AgentEvaluation 既有模型。
- 为尚未完成的 V3-2 到 V3-6 能力预留输出槽位，但不在 V3-1 假装已经实现完整智能链路。
- 每次运行生成结构化 JSON 和可读 Markdown，方便没有前端时人工查看。

V3-1 推荐的最高测试接缝是一个新的应用层 harness 入口。它可以被命令行 runner、Maven profile 或 dedicated integration test 调用，但核心断言应落在应用层输出结果，而不是内部 helper。

V3-1 的核心流程是：

```text
加载 fixture
  -> 初始化 Task / ApiSpec / KnowledgeDocument / MemoryItem / TestCase 或草稿数据
  -> 使用 fake provider / fake HTTP gateway 运行一次受控 suite agent run
  -> 收集 generated suite draft、execution result、variable audit、failure analysis、memory feedback、evaluation comparison
  -> 脱敏输出
  -> 写出 JSON report 和 Markdown report
```

V3-1 可以先使用 fixture 中的 deterministic suite draft 或 staged summary 来代表后续 V3-2/V3-3 的输出，但必须在报告里明确标记哪些内容来自 fixture/stub，哪些内容来自已接入的真实应用服务。后续 phase 接入真实 Business Flow Discovery、DependencyLinker、ExecutionContext 和 FailureAnalysis 时，应替换这些 staged summary，而不是新增另一套演示入口。

## User Stories

1. As an API 测试 Agent 使用者, I want 一条命令运行 V3 suite agent demo, so that 没有前端时我也能手动验证系统。
2. As an API 测试 Agent 使用者, I want harness 使用固定 fixture, so that 每次运行结果稳定可复现。
3. As an API 测试 Agent 使用者, I want fixture 包含订单链路样例, so that 我可以演示创建订单、支付订单、查询订单这类真实业务流程。
4. As an API 测试 Agent 使用者, I want fixture 包含鉴权变量, so that 链路测试能覆盖 token、tenant、baseUrl 等执行前置条件。
5. As an API 测试 Agent 使用者, I want fixture 包含 fake HTTP response, so that harness 不依赖真实外部服务。
6. As an API 测试 Agent 使用者, I want fixture 包含 business_flow 文档, so that 后续 Business Flow Discovery 能从同一个样例接入。
7. As an API 测试 Agent 使用者, I want fixture 包含 LongTermMemory 样例, so that 后续记忆复用和失败经验沉淀能被演示。
8. As an API 测试 Agent 使用者, I want harness 默认使用 fake provider, so that CI 和本地回归不会因为真实 LLM 波动而失败。
9. As an API 测试 Agent 使用者, I want manual real LLM 模式必须显式开启, so that 真实 LLM 不会被误用到默认测试里。
10. As an API 测试 Agent 使用者, I want manual real LLM 模式在未配置时清晰失败, so that 我知道需要补哪些配置。
11. As an API 测试 Agent 使用者, I want harness 默认使用 fake HTTP gateway, so that 不会误打真实业务接口。
12. As an API 测试 Agent 使用者, I want harness 能初始化 Task, so that 演示流程仍然符合 ProbeFlow 的任务模型。
13. As an API 测试 Agent 使用者, I want harness 能初始化 ApiSpec, so that 演示链路有真实接口资产输入。
14. As an API 测试 Agent 使用者, I want harness 能初始化 KnowledgeDocument, so that RAG 文档知识能作为后续链路发现输入。
15. As an API 测试 Agent 使用者, I want harness 能初始化 MemoryItem, so that 长期经验能作为后续链路决策输入。
16. As an API 测试 Agent 使用者, I want harness 能初始化 SUITE 草稿摘要, so that 后续 DependencyLinker 未完成前也能展示输出位置。
17. As an API 测试 Agent 使用者, I want harness 报告区分真实服务输出和 staged summary, so that 我不会误以为 V3-1 已完成全部智能能力。
18. As an API 测试 Agent 使用者, I want harness 输出 run summary, so that 我能快速知道运行状态、provider mode、fixture、耗时和结果。
19. As an API 测试 Agent 使用者, I want harness 输出 generated suite draft, so that 我能查看步骤顺序、step name、critical 标记和来源摘要。
20. As an API 测试 Agent 使用者, I want harness 输出 execution result, so that 我能查看每个步骤或样例 case 的执行状态。
21. As an API 测试 Agent 使用者, I want harness 输出 variable audit, so that 后续 ExecutionContext 接入后可以展示变量从哪里来到哪里去。
22. As an API 测试 Agent 使用者, I want harness 输出 failure analysis summary, so that 后续 Suite Failure Analysis 接入后可以展示根因和建议。
23. As an API 测试 Agent 使用者, I want harness 输出 memory feedback summary, so that 后续 Memory Feedback 接入后可以展示 Agent 学到了什么。
24. As an API 测试 Agent 使用者, I want harness 输出 evaluation comparison, so that 后续 fake 和 real LLM 对比有稳定展示位置。
25. As an API 测试 Agent 使用者, I want harness 写出 JSON 报告, so that 机器和测试可以稳定断言结构化结果。
26. As an API 测试 Agent 使用者, I want harness 写出 Markdown 报告, so that 我可以直接打开阅读和面试演示。
27. As an API 测试 Agent 使用者, I want Markdown 报告包含清晰章节, so that 我能按输入、生成、执行、分析、记忆、评测的顺序讲解。
28. As an API 测试 Agent 使用者, I want JSON 报告包含 schemaVersion, so that 后续 phase 修改报告结构时能兼容。
29. As an API 测试 Agent 使用者, I want 报告包含 fixture version, so that 我能知道一次运行基于哪个演示样例。
30. As an API 测试 Agent 使用者, I want 报告包含 provider mode, so that 我能区分 fake deterministic 和 manual real LLM。
31. As an API 测试 Agent 使用者, I want 报告包含 usesRealLlm 标记, so that 我能快速判断这次是否调用真实模型。
32. As an API 测试 Agent 使用者, I want 报告包含 usesExternalHttp 标记, so that 我能确认没有误打外部服务。
33. As an API 测试 Agent 使用者, I want 报告中的 token、cookie、authorization、password、secret 被脱敏, so that 演示和日志不会泄露敏感信息。
34. As an API 测试 Agent 使用者, I want harness 在输出前统一脱敏, so that JSON、Markdown、日志和错误诊断保持一致。
35. As an API 测试 Agent 使用者, I want harness 失败时返回结构化错误, so that 我知道是 fixture、provider、HTTP gateway、输出目录还是执行链路的问题。
36. As an API 测试 Agent 使用者, I want harness 对未知 fixture 给出清晰错误, so that 同事可以快速修正命令参数。
37. As an API 测试 Agent 使用者, I want harness 对不允许的 provider mode 给出清晰错误, so that 真实 LLM 边界不会被绕过。
38. As an API 测试 Agent 使用者, I want harness 能覆盖成功链路, so that 我可以演示正常业务流通过。
39. As an API 测试 Agent 使用者, I want harness 能覆盖失败链路, so that 我可以演示前置失败或变量缺失的分析位置。
40. As an API 测试 Agent 使用者, I want harness 输出结果稳定排序, so that 文档、测试和截图不会因为 Map 顺序抖动。
41. As an API 测试 Agent 使用者, I want harness 不改变正式生产配置, so that 它只作为开发和演示工具存在。
42. As an API 测试 Agent 使用者, I want harness 不引入完整 Web Console, so that V3-1 不会偏离后端验证目标。
43. As an API 测试 Agent 使用者, I want harness 不引入产品化 REST Controller, so that 第一个 phase 保持轻量。
44. As an API 测试 Agent 使用者, I want harness 不要求真实 embedding, so that 运行成本和本地门槛保持低。
45. As an API 测试 Agent 使用者, I want harness 可以被后续 Light Console 复用, so that V3-6 前端不用重新发明一套运行逻辑。
46. As an API 测试 Agent 使用者, I want harness 可以被 Agent Evaluation 复用, so that 后续评测能基于同一组 fixture。
47. As an API 测试 Agent 使用者, I want harness 可以逐步替换 staged summary 为真实 V3 模块输出, so that 后续 phase 能自然接入。
48. As an API 测试 Agent 使用者, I want harness 文档说明如何运行, so that 同事拉取项目后可以快速复现。
49. As an API 测试 Agent 使用者, I want harness 运行后能告诉我报告位置, so that 我不用在项目里到处找输出。
50. As an API 测试 Agent 使用者, I want V3-1 完成后形成可演示的第一屏材料, so that 我能向面试官说明后续智能链路 Agent 的总装入口已经存在。

## Implementation Decisions

- V3-1 定位为 V3 的手动验证底座，不宣称完成完整智能链路 Agent。
- 最高测试接缝是应用层 harness 入口。命令行 runner、Maven profile 或 integration test 可以调用它，但核心逻辑应集中在可测试的应用层服务中。
- 新增 `Manual Suite Agent Harness` 或等价应用层入口，负责加载 fixture、协调现有服务、聚合结果、脱敏并写出报告。
- Harness 不应把业务逻辑写在 runner 或测试类里。runner 只负责解析参数、调用应用层入口和展示输出位置。
- Harness 运行模式至少包含 deterministic fake 模式。manual real LLM 模式可以预留或接入，但必须显式开启并受 LLM policy 约束。
- Provider mode 必须记录到 run summary 和报告中，至少区分 deterministic fake 与 manual real LLM。
- 默认 provider mode 必须是 deterministic fake。
- 默认 HTTP 执行必须使用 fake HTTP gateway 或等价受控网关，不允许默认访问真实外部 HTTP 服务。
- Fixture 应作为稳定测试资产存在，包含 ApiSpec、business_flow 文档、LongTermMemory、env/auth/test data、fake HTTP response、expected suite draft summary 和 expected report markers。
- Fixture 首版应覆盖至少一个成功订单链路和一个失败链路。失败链路可以先使用 staged failure summary，为 V3-5 的真实失败分类预留位置。
- Fixture 加载应产生稳定、可排序、可审计的数据，避免同一份 fixture 每次生成不同 id 导致报告不可比。
- Harness 应复用现有 Task 模型。一次 run 应有 taskId、taskName、taskType、sourceType、promotionMode、targetApiSpecIds 和 metadata。
- Harness 应复用现有 ApiSpec 模型。fixture 中的接口资产应能映射为 ApiSpec，而不是定义另一套接口描述模型。
- Harness 应复用 KnowledgeDocument / KnowledgeChunk 或等价知识资产入口，为后续 V3-2 的 Business Flow Discovery 提供 business_flow 上下文。
- Harness 应复用 MemoryItem 或 LongTermMemory 相关模型，为后续 V3-2/V3-6 的记忆输入和记忆反馈提供上下文。
- Harness 可以在 V3-1 使用 fixture-provided suite draft summary 代表后续 V3-2/V3-3 输出，但必须标记来源为 staged 或 fixture。
- Harness 可以在 V3-1 使用现有 HTTP 执行能力执行已有 TestCase 或受控样例请求；如果完整 SUITE 变量传递尚未实现，报告应明确标记 variable audit 为 staged 或 pending-runtime。
- Harness 应输出一个 run result 聚合对象。它应包含 run summary、fixture summary、generated suite draft、execution result、variable audit、failure analysis、memory feedback summary、evaluation comparison、artifact references 和 diagnostics。
- JSON report 应是机器可读的稳定结构，包含 schemaVersion、runId、fixtureId、fixtureVersion、providerMode、startedAt、completedAt、status、sections 和 diagnostics。
- Markdown report 应是人类可读的演示材料，按输入、链路草稿、执行结果、变量审计、失败分析、记忆反馈、评测对比和安全边界组织。
- JSON 和 Markdown 输出应来自同一个脱敏后的 run result，避免两份报告信息不一致。
- 脱敏应覆盖 authorization、cookie、password、secret、token、apiKey、credential 等敏感键名，也应覆盖常见 header 和 nested map 中的敏感字段。
- Harness 失败时应返回结构化诊断，区分 fixture not found、fixture invalid、provider blocked、external HTTP disabled、report write failed 和 run failed。
- Harness 不应吞掉失败。失败 run 也应尽可能写出诊断报告，方便开发者定位。
- Harness 应支持输出目录配置，但默认输出位置应在本地构建或临时输出区域，不污染源码主路径。
- Harness 应为后续 V3 Light Console 预留可复用的查询模型或结果模型，但 V3-1 不做前端。
- Harness 应为后续 Agent Evaluation 预留 fixture id、capability tags 和 expected markers，但 V3-1 不要求完成 V3 专属 evaluator。
- V3-1 不新增新的持久化核心表，除非实现发现必须记录 run artifact。首选文件产物和已有实体，避免为了演示入口引入过早 schema。
- 如果必须新增配置项，应默认关闭真实 LLM 和外部 HTTP，并在测试 profile 中强制 fake。
- Harness 的公开命名应围绕 SUITE、Agent、Harness、Fixture、Run、Artifact、Report，避免引入与领域术语冲突的新词。
- 实现时应保持 V1/V2 既有 Maven 测试稳定，不得破坏已有 Task、HttpExecution、Report、Memory、AgentEvaluation 流程。

## Testing Decisions

- 测试应优先断言 harness 的外部行为：给定 fixture 和 fake provider，运行后得到稳定 run result、JSON report、Markdown report 和脱敏诊断。
- 测试不应断言内部 helper、私有排序临时变量、具体日志文本、具体 Markdown 空白格式或未契约化的 Map 遍历顺序。
- 需要新增 harness 应用层测试：给定成功 fixture，运行后状态为 passed 或 completed，并输出 run summary、suite draft summary、execution summary、variable audit summary、failure analysis summary、memory feedback summary 和 report artifact references。
- 需要新增 fixture 加载测试：未知 fixture 应返回清晰错误；缺少必填字段的 fixture 应返回 fixture invalid 诊断。
- 需要新增 fake provider 边界测试：默认 provider mode 为 deterministic fake，报告中 `usesRealLlm=false`。
- 需要新增 manual real LLM 安全测试：未显式配置真实 LLM 时，manual real LLM 模式不得启动，并返回 policy blocked 诊断。
- 需要新增 fake HTTP gateway 边界测试：默认 run 不访问真实外部 HTTP，报告中 `usesExternalHttp=false`。
- 需要新增报告输出测试：一次 run 应写出 JSON 和 Markdown 两种产物，且两者都包含相同 runId、fixtureId、providerMode 和 status。
- 需要新增报告 schema 测试：JSON report 必须包含 schemaVersion、run summary、sections、diagnostics 和 artifact metadata。
- 需要新增 Markdown 可读性测试：Markdown report 应包含核心章节标题，方便人工阅读和演示。
- 需要新增脱敏测试：env、auth、headers、body、diagnostics、JSON report 和 Markdown report 中不得泄露 token、authorization、cookie、password、secret、apiKey。
- 需要新增失败 run 产物测试：fixture invalid 或 provider blocked 时，也应尽可能写出诊断 report 或返回可读 artifact failure reason。
- 需要新增稳定性测试：同一 fixture 连续运行两次，除 runId、时间戳、耗时和输出路径外，核心结果应保持稳定。
- 需要新增 stage marker 测试：V3-1 中尚未真实接入的 suite draft、variable audit、failure analysis 或 memory feedback，应在报告中明确标记 staged / fixture / pending-runtime。
- 需要新增 boundary guard 测试：V3-1 不应引入完整 Web Console、产品化 REST Controller、UI 自动化、Service 直调、DB 直连断言、真实外部 LLM 必需依赖、真实外部 API 必需依赖或真实 embedding 必需依赖。
- 可以复用现有测试先例：HTTP 执行应用层测试、报告生成应用层测试、Agent Evaluation deterministic fixture 测试、Memory Feedback 闭环测试、LLM policy 测试和各 phase boundary guard 测试。
- 完整验收应运行后端 Maven 测试套件；至少要保证新增 harness 测试与现有核心测试在 fake provider 模式下稳定通过。

## Out of Scope

- 不实现完整 Business Flow Discovery。V3-1 可以加载 business_flow fixture 和 staged candidate summary，但真实链路发现属于 V3-2。
- 不实现完整 DependencyLinker。V3-1 可以展示 expected suite draft 或 staged dependency summary，但真实 producer / consumer 推导和 `extractRules` 生成属于 V3-3。
- 不实现完整 ExecutionContext、VariableResolver、ResponseExtractor 和 VariableWriteBackService。V3-1 只定义 variable audit 输出位置，真实运行时变量系统属于 V3-4。
- 不实现完整 Suite Failure Analysis、Replanning 和 Human-in-the-loop 链路恢复。V3-1 只定义 failure analysis 输出位置，真实失败分类和恢复属于 V3-5。
- 不实现完整 Memory Feedback 和 V3 Agent Evaluation。V3-1 只定义 memory feedback summary 和 evaluation comparison 输出位置，完整能力属于 V3-6。
- 不实现 V3 Light Console。V3-1 的产物应能被后续 Light Console 复用，但不做页面。
- 不做完整版 RAG + Memory。真实 embedding provider、pgvector、BM25、Query Rewrite、多路召回、Small-to-Big 父子索引和记忆治理仍属于 V4 或后续版本。
- 不做完整 Web Console、产品化 REST Controller、权限系统、团队协作、任务列表管理或可视化链路编排。
- 不做 UI 自动化、浏览器自动化、Service 直调、DB 直连断言、消息队列测试或非 HTTP 协议测试。
- 不要求真实 LLM、真实 embedding 或真实目标 HTTP 服务参与 CI。
- 不自动修改既有 SUITE 资产。V3-1 只做 harness 和 fixture，不改变旧资产刷新策略。

## Further Notes

V3-1 是 V3 的“总装台”。它本身不负责把所有智能能力一次性做完，但它要让后续每个 phase 都有地方接入。

推荐面试讲法：

```text
我在做智能链路 Agent 前，先做了一个 Manual Suite Agent Harness。
它把固定业务 fixture、fake provider、fake HTTP gateway、任务初始化、执行结果、失败分析摘要、记忆反馈摘要和报告产物串起来。
这样后续链路发现、依赖推导、变量上下文、失败分析和记忆学习都不是孤立模块，而是逐步接入同一个可演示闭环。
```

V3-1 完成后的理想演示效果：

```text
开发者运行一个本地命令。
系统加载订单支付 fixture。
报告展示这次 run 的输入、候选 SUITE 草稿、执行摘要、变量审计槽位、失败分析槽位、记忆反馈槽位和安全边界。
所有真实外部依赖默认关闭。
JSON 用于测试断言，Markdown 用于人工阅读和面试演示。
```

V3-1 与后续 phase 的关系：

- V3-2 把 staged flow discovery 替换为真实 Business Flow Discovery。
- V3-3 把 staged suite draft 替换为真实 DependencyLinker 和 SUITE 草稿生成。
- V3-4 把 staged variable audit 替换为真实 ExecutionContext、变量解析、响应提取和写回。
- V3-5 把 staged failure analysis 替换为真实 Suite Failure Analysis、Replanning 和 Human-in-the-loop。
- V3-6 把 staged memory feedback / evaluation / presentation 替换为真实 Memory Feedback、Agent Evaluation 和 V3 Light Console。
