# ProbeFlow Test Agent

ProbeFlow Test Agent 是一个面向后端接口测试的智能测试 Agent 平台。它的目标不是做一个普通的接口管理工具，也不是简单调用大模型生成几条测试用例，而是构建一套能够理解项目、沉淀经验、自动编排工具、持续改进测试质量的 API 测试智能体系统。

最终形态下，ProbeFlow 会围绕一个完整闭环运行：

```text
导入代码 / OpenAPI / 业务文档
-> 自动分析接口与业务上下文
-> 生成 SINGLE / SUITE / BATCH 测试资产
-> 执行 HTTP 接口测试
-> 结构化分析结果
-> 沉淀知识与记忆
-> 反哺下一次生成、执行和失败分析
```

这个项目是全新开发的系统，不是在 `AGI-saber-java` 上改造。仓库中的 `ClaudeCode-main` 只作为 Agent 编排、任务状态、工具路由、上下文管理等思想参考，不作为 ProbeFlow 的技术基座。

## 项目定位

ProbeFlow 专注于 HTTP/HTTPS REST API 测试，核心能力包括：

- 从 Spring Boot Controller、OpenAPI、Swagger 等来源自动识别接口资产。
- 结合业务文档、测试规范、错误码说明、历史报告等知识生成更贴近业务的测试用例。
- 支持单接口测试、链路测试、批量回归测试三类执行组织方式。
- 在执行后自动分析失败原因、断言缺口、业务风险和可复用经验。
- 把一次次测试过程中得到的经验沉淀成长期记忆，后续任务可以自动召回。
- 用 Agent Loop 方式驱动任务，而不是写死一条固定流程。

ProbeFlow 的目标用户不是只想“发一个 HTTP 请求”的人，而是希望把接口测试、回归测试、测试资产维护和测试经验复用自动化起来的研发团队、测试团队和平台工程团队。

## 终极版系统架构

ProbeFlow 最终会由九个核心系统组成：

```text
Source Material Layer
-> API Analysis System
-> Knowledge Base System
-> Memory System
-> Unified Context Builder
-> Case Generation System
-> Assertion & Rule System
-> Orchestration System
-> Execution & Report System
```

各系统不是孤立模块，而是围绕 `Task` 运行。一次 Task 可以是接口探索、用例生成、回归执行、失败分析或报告生成。Task 由编排系统驱动，每一轮都先构建上下文，再决定下一步要调用什么工具。

## 核心工作流

典型任务链路如下：

```text
1. 用户上传源码、OpenAPI、Swagger 或业务文档
2. SourceMaterial 保存原始输入物料
3. API Analysis System 识别接口结构，生成 ApiSpec
4. Knowledge Base System 检索相关业务知识、测试规范和接口补充说明
5. Memory System 召回历史失败模式、测试偏好和项目经验
6. Unified Context Builder 组装当前任务需要的 ContextBundle
7. Case Generation System 生成 TestCaseDraft
8. 用户确认或系统自动提升为正式 TestCase
9. Execution Engine 执行接口测试
10. Assertion System 判断响应是否符合预期
11. Observation 分析执行结果
12. Memory Refinery 提纯可复用经验
13. Report 输出最终测试报告
```

这条链路可以全自动运行，也可以在关键节点暂停，让用户确认、编辑或丢弃生成结果。

## 领域对象

ProbeFlow 使用一组稳定的领域对象描述整个测试生命周期：

- `SourceMaterial`：用户输入的原始物料，例如源码、OpenAPI、Swagger、业务文档。
- `ApiSpec`：接口定义资产，来自代码分析、OpenAPI 导入或手工录入。
- `TestCase`：正式测试用例资产。
- `TestCaseStep`：链路测试中的单个步骤，绑定一个接口。
- `TestCaseDraft`：任务过程中生成的用例草稿，确认后才能成为正式资产。
- `Task`：一次生成、执行、分析或回归过程，是 Agent 编排的运行单位。
- `PlanStep`：编排系统给出的下一步动作。
- `StepOutcome`：工具执行后的运行时结果摘要，服务于主循环决策。
- `ExecutionRecord`：接口执行的原始事实。
- `Observation`：对执行结果的结构化分析。
- `KnowledgeDocument`：知识库中的稳定文档资产。
- `KnowledgeChunk`：可被检索召回的知识片段。
- `MemoryItem`：Agent 从历史运行中提炼出的可复用经验。
- `Report`：任务最终输出。

## API 分析系统

API 分析系统负责让 Agent 知道“项目里有哪些接口、接口长什么样、接口大概怎么工作”。

它会从多种输入中抽取接口信息：

- Spring Boot Controller 代码
- OpenAPI / Swagger 文件
- Postman Collection
- 手工录入接口
- 历史测试资产

在 Spring Boot 项目中，系统会重点识别：

- `@RestController`
- `@RequestMapping`
- `@GetMapping`
- `@PostMapping`
- `@RequestParam`
- `@PathVariable`
- `@RequestBody`
- `@Validated`
- `@PreAuthorize`

分析结果不会只是简单的路由列表，而是面向测试生成的统一接口上下文，包括路径、方法、参数、DTO 字段、返回模型、校验规则、鉴权要求、业务实体、风险提示和可生成测试点。

## 知识库系统

知识库系统，也就是 ProbeFlow 里的 RAG 系统，保存的是“项目原本就知道的东西”。它面向人类已经写好的稳定文档，而不是 Agent 运行后产生的经验。

一句话说清楚：

```text
RAG 不是记忆。
RAG 管文档知识：业务规则、接口说明、测试规范、错误码说明、环境说明。
Memory 管运行经验：历史失败模式、用户偏好、测试策略、项目踩坑经验。
```

知识库面向的输入包括：

- PRD
- 业务流程文档
- 接口说明
- 测试规范
- 错误码说明
- FAQ
- 环境说明
- 事故复盘

RAG 的核心价值不是“存文档”，而是把这些文档变成 Agent 在不同任务阶段可以精准消费的上下文。比如生成测试用例时，它要召回测试规范、业务规则、接口说明；失败分析时，它要召回错误码文档、事故复盘、环境说明。

### RAG 写入链路

知识库不是简单把文档丢进向量库，而是先治理再索引：

```text
Raw Documents
-> KnowledgeIngestService
-> KnowledgeChunker
-> KnowledgeMetadataExtractor
-> KnowledgeDocument / KnowledgeDocumentRevision
-> KnowledgeChunk
-> Embedding
-> KnowledgeIndex
```

写入时会做几件事：

- `KnowledgeIngestService` 负责接收 Markdown、PRD、Wiki 导出、接口说明、测试规范、事故复盘等文档。
- `KnowledgeChunker` 按标题、段落、表格、列表等语义结构切块，尽量保证一个 chunk 是完整的业务含义。
- `KnowledgeMetadataExtractor` 提取系统名、模块名、接口路径、业务实体、错误码、文档类型、适用阶段、标签、权威等级、更新时间。
- `KnowledgeDocumentRevision` 保存文档版本，避免文档更新后无法追溯当时 Agent 用的是哪一版知识。
- `KnowledgeChunk` 保存可检索片段，是 RAG 召回、引用溯源、反馈调权的最小单位。
- `Embedding` 用于语义召回，但不会替代结构化元数据。

### RAG 检索链路

RAG 的读取链路采用“阶段化 query + 混合召回 + 重排 + 压缩组装”：

```text
Current Task / ApiSpec / FailureInfo
-> KnowledgeQueryBuilder
-> Structure Filter
-> Tag Filter
-> Semantic Recall
-> KnowledgeReranker
-> KnowledgeContextBuilder
-> ContextBundle
```

不同阶段会使用不同的召回策略：

- `API_ANALYSIS_PROFILE`：接口分析阶段，优先召回业务流程、接口补充说明、领域规则、环境说明。
- `CASE_GENERATION_PROFILE`：用例生成阶段，优先召回测试规范、业务规则、历史事故、接口说明。
- `FAILURE_ANALYSIS_PROFILE`：失败分析阶段，优先召回错误码说明、事故复盘、环境文档、相似接口说明。

RAG 不只靠向量相似度，而是组合多种信号：

- 结构过滤：`system`、`module`、`apiPath`、`httpMethod`、`bizEntity`、`docType`。
- 标签过滤：`auth`、`payment`、`order`、`risk`、`error-code`、`test-spec` 等。
- 语义召回：根据 query embedding 查找语义相似 chunk。
- 权威性增强：官方文档、近期更新、人工确认过的文档权重更高。
- 阶段适配：同一个 chunk 在用例生成阶段有用，不代表失败分析阶段也应该靠前。

召回结果不会把原文整段塞给模型，而是先整理成结构化上下文：

```text
KnowledgeContext {
  businessRules: [...]
  apiNotes: [...]
  testSpecs: [...]
  errorCodeGuides: [...]
  incidentHints: [...]
  citedChunks: [...]
}
```

知识库主要解决的是：

- 这个接口属于哪个业务流程。
- 这个接口有哪些业务前置条件。
- 哪些字段有业务约束。
- 哪些错误码需要重点断言。
- 哪些测试规范必须遵守。
- 失败分析时应该参考哪些历史事故或规则。

### RAG 反馈调权

RAG 会接收任务反馈，但它不会把反馈直接写成长期记忆。它主要调整已有文档 chunk 的统计权重：

- 某个 chunk 经常被召回且帮助生成了有效用例，提升 `successContribution`。
- 某个 chunk 被召回后经常被用户忽略，降低排序权重。
- 某个文档过期导致错误建议，降低 authority 或标记 stale。
- 某个错误码说明多次解释失败原因，失败分析阶段权重提高。

所以 RAG 的反馈目标是“让文档知识更好用”，不是“从执行结果中学习经验”。从执行结果中学习经验，是记忆系统的职责。

## 记忆系统

记忆系统保存的是“Agent 后来学到的东西”。它和知识库是平级关系，不互相替代。它不是文档检索系统，而是经验沉淀系统。

一句话区分：

```text
知识库回答：项目本来写下了什么？
记忆系统回答：Agent 在运行中学会了什么？
```

举例：

- 文档里写着“支付接口 sign 字段必须参与签名”，这是 RAG 知识。
- Agent 多次执行发现“测试环境里 sign 字段必须放在 body 最后，否则返回 401”，这是 Memory 经验。
- 文档里写着“订单创建后才能支付”，这是 RAG 知识。
- Agent 多次失败后总结出“支付接口失败时优先检查订单状态是否仍为 INIT”，这是 Memory 经验。

ProbeFlow 的记忆系统分为五层：

- `Session Memory`：当前会话的短期上下文。
- `Task Memory`：当前任务中的持续状态和执行事实。
- `Memory Refinery`：记忆提纯层，负责抽取、分类、去重、合并和压缩。
- `Refined Long-term Memory`：跨任务可复用的长期经验。
- `Memory Retriever`：按任务阶段召回长期经验。

可选还会有 `Team Knowledge Memory`，用于承载团队人工维护的规范、项目说明和复盘材料。

### Session Memory

Session Memory 保存当前会话内的短期信息，例如当前目标、最近几轮结论、最近工具调用摘要、最近一次失败原因和当前阶段的中间决策。它生命周期短、更新频繁，适合放在 Redis 或本地缓存中，并使用 TTL 自动过期。

### Task Memory

Task Memory 保存当前任务的结构化事实，例如需求摘要、接口摘要、已生成用例、执行记录、断言结果、失败样本、分析结论和下一步建议。它服务于任务恢复、断点续跑和多轮 Agent Loop。

Task Memory 通常按 `taskId` 精确查询，不需要复杂检索。它回答的是：

- 当前任务已经做到哪一步。
- 哪些接口已经分析过。
- 哪些用例已经生成。
- 哪些请求已经执行。
- 哪些断言失败了。
- 下一步为什么要继续或停止。

Task Memory 是任务内事实，不等于长期经验。任务结束后，只有经过 Memory Refinery 判断有复用价值的内容，才会进入 Long-term Memory。

### Memory Refinery

Memory Refinery 是所有长期记忆写入的入口。原始对话、工具输出和错误日志不会直接进入长期记忆，而是先经过：

```text
extract -> classify -> deduplicate -> merge -> compress -> tag -> embed
```

它的写入链路更完整地说是：

```text
Observation / Task Memory / 用户修订 / 执行失败
-> MemoryCandidate
-> Value Judgment
-> Memory Refinery
-> Deduplicate or Merge
-> Long-term Memory
```

Memory Refinery 会先判断一个候选内容是否值得沉淀：

- 是否跨任务可复用。
- 是否能帮助后续生成、执行或失败分析。
- 是否能归纳成稳定模式。
- 是否能被标签化、检索和解释。
- 是否不是一次性噪声。

适合沉淀成长期记忆的信息包括：

- 某类接口常见失败模式。
- 某项目特殊鉴权规则。
- 某业务领域的边界测试策略。
- 某类接口常用断言模板。
- 用户偏好的测试风格和报告格式。

不适合直接沉淀的信息包括一次性日志全文、临时推理过程、低价值工具输出和无法泛化的对话片段。

### Memory 提纯示例

原始执行事实可能是：

```text
POST /api/pay 返回 401。
请求体中 sign 字段放在 amount 前面。
同样参数把 sign 移到 body 最后后请求成功。
用户确认这是测试环境网关的签名校验限制。
```

提纯后的长期记忆应该是：

```text
type: project_knowledge
title: 支付接口 body 签名字段顺序约束
summary: 支付相关接口在测试环境中要求 sign 字段位于 body 最后位置，否则可能返回 401。
scope: project/payment
tags: [payment, signature, test-env, 401, body-order]
trigger: 生成或执行支付相关接口用例时
confidence: 0.85
```

这条记忆后续会影响：

- 用例生成：生成支付接口请求模板时把 sign 放在最后。
- 执行前准备：检查请求体字段顺序。
- 失败分析：遇到 401 时优先提示签名字段顺序风险。

### Long-term Memory

长期记忆存储跨任务仍然有价值的经验，典型类型包括：

- `project_knowledge`
- `testing_pattern`
- `failure_pattern`
- `preference`
- `domain_rule`

长期记忆建议使用 PostgreSQL + pgvector 存储，同时支持结构化过滤、标签召回和向量召回。每条记忆需要记录类型、标题、摘要、完整内容、适用范围、来源任务、触发条件、标签、重要性、置信度、命中次数、贡献度和最近使用时间。

长期记忆的召回不是“拿最像的文本”，而是按任务阶段组合评分：

```text
生成 case：testing_pattern + project_knowledge + preference
执行前：project_knowledge + failure_pattern + auth/env 相关经验
失败分析：failure_pattern + errorCode + apiPath + 相似响应摘要
报告生成：高贡献 failure_pattern + 本次任务新增经验
```

一个默认的排序思路是：

```text
finalScore =
  structure_match
  + tag_match
  + vector_similarity
  + importance
  + successContribution
  - stalenessPenalty
```

失败分析阶段会额外提高 `failure_match`、`errorCode_match`、`response_pattern_match` 的权重。

### Memory 生命周期

记忆需要持续治理，否则长期记忆会变成噪声库：

- 新记忆进入 `ACTIVE` 状态。
- 相似记忆会被合并，增加版本号，而不是无限新增重复项。
- 长期没有命中、贡献度低、置信度低的记忆会降权。
- 被证明过期或错误的记忆会变成 `INACTIVE` 或 `ARCHIVED`。
- 用户明确修正过的记忆会提高置信度，并保留修订来源。

记忆系统的目标不是越记越多，而是越记越准。

## RAG 与 Memory 的协同关系

RAG 和 Memory 会在同一个任务里同时被使用，但职责完全不同：

```text
            写入来源                  保存内容                 主要用途
RAG         人类文档                  稳定知识                 补业务背景、规范、错误码、流程
Memory      执行结果和用户行为         运行经验                 复用失败模式、测试策略、偏好
TaskMemory  当前任务过程               当前事实                 恢复任务、驱动下一步编排
Session     当前会话                   短期上下文               保持最近决策连续性
```

一次失败分析中，它们会这样配合：

```text
ExecutionRecord 显示 /api/pay 返回 401
-> RAG 召回错误码文档：401 可能是签名错误或 token 过期
-> Memory 召回历史经验：支付接口 sign 字段顺序错误也会导致 401
-> Task Memory 提供本次请求体和断言失败摘要
-> Unified Context Builder 合并成失败分析上下文
-> Agent 输出更可信的失败原因和下一步建议
```

一次用例生成中，它们会这样配合：

```text
ApiSpec 显示 POST /api/order/{id}/pay
-> RAG 召回业务流程：订单必须 CREATED 后才能支付
-> RAG 召回测试规范：金额字段必须测 0、负数、超限、小数精度
-> Memory 召回项目经验：支付接口 sign 字段必须放在 body 最后
-> Memory 召回团队偏好：每个关键业务接口至少生成正例、鉴权失败、业务状态非法三类 case
-> Case Generation System 生成更贴合项目的 TestCaseDraft
```

这就是 ProbeFlow 区别于普通 RAG 应用的地方：它不是只查文档，也不是只记历史，而是把“文档知识”和“运行经验”分别治理，再在具体任务里合并使用。

## 统一上下文构建器

Unified Context Builder 是 ProbeFlow 的读路径核心。它不负责存储，也不负责生成，而是负责在每一个任务阶段回答一个问题：

```text
当前这一步到底需要哪些上下文？
```

它会从多个来源组装 `ContextBundle`：

- 当前用户输入
- Task 状态
- Session Memory
- Task Memory
- Long-term Memory
- Knowledge RAG
- ApiSpec / CodeContext
- 历史执行记录
- 当前环境配置

最终输出不是一段散文，而是分区后的结构化上下文：

```text
ContextBundle {
  taskGoal: 当前任务目标
  apiContext: ApiSpec + CodeContext
  taskState: 当前 Task 状态和已完成步骤
  sessionContext: 最近会话决策
  taskMemory: 当前任务事实
  knowledgeContext: RAG 召回的文档知识
  longTermMemoryContext: Memory 召回的长期经验
  executionContext: 环境、变量、鉴权、执行配置
  constraints: 本轮必须遵守的规则
  citations: 文档 chunk 和 memory item 来源
}
```

不同阶段需要不同上下文。例如：

- 生成测试用例时，需要接口结构、字段约束、业务规则、历史测试模式。
- 执行接口测试时，需要环境变量、鉴权方式、请求模板、变量依赖。
- 分析失败时，需要原始请求响应、断言结果、历史失败模式、相关文档说明。
- 生成报告时，需要任务目标、执行摘要、失败分类、风险建议和可复用结论。

合并时遵循几个规则：

- 当前任务事实优先于长期记忆，因为 Task Memory 反映的是本次真实执行。
- 官方文档优先于低置信度记忆，但高置信度历史失败模式会作为风险提示保留。
- RAG 输出必须带文档来源，Memory 输出必须带来源任务、置信度和适用范围。
- 如果 RAG 和 Memory 冲突，不直接静默覆盖，而是在 ContextBundle 中标记 conflict，交给 Planner 或分析器决策。
- 上下文有 token 预算，先保留当前任务事实，再保留高权威 RAG，最后保留高贡献 Memory。

这个模块避免把所有历史信息一股脑塞给模型，而是按阶段、按目标、按预算构造刚好够用、可追溯、可解释的上下文。

## 测试用例生成系统

ProbeFlow 支持三种执行组织方式：

- `SINGLE`：单个 HTTP 接口用例。
- `SUITE`：多个 HTTP 接口按顺序组成的链路用例。
- `BATCH`：多个 HTTP 接口用例批量或并发执行。

### SINGLE 生成

SINGLE 是单接口驱动的生成链路：

```text
StructureCaseGenerator
-> BusinessCaseGenerator
-> MemoryCaseEnhancer
-> AssertionSuggestionGenerator
-> CaseNormalizer
```

它主要覆盖接口结构、参数边界、业务约束、鉴权要求、异常输入和断言建议。

### SUITE 生成

SUITE 是跨接口链路生成，不是简单把多个 SINGLE 拼在一起。它遵循“先有 SINGLE，后有 SUITE”的策略：

```text
生成每个接口的 SINGLE case
-> 识别业务链路顺序
-> 分析步骤间变量依赖
-> 生成 extractRules
-> 在后续步骤中引用 ${suite.xxx}
-> 生成链路级断言和异常场景
```

SUITE 中的 `TestCaseStep` 是快照，不是对 SINGLE 用例的运行时引用。这样可以保证链路资产稳定，避免底层接口用例变化导致历史回归行为漂移。

### BATCH 执行

BATCH 用于批量回归、并发执行和多接口测试集合。它关注执行组织、并发控制、失败隔离、结果汇总和报告输出。

## 规则与断言系统

规则系统负责把接口结构、业务约束和历史经验转成测试输入变化策略。例如：

- 必填字段缺失。
- 字符串长度边界。
- 枚举非法值。
- 数值上下界。
- 鉴权 Header 缺失。
- Token 过期。
- 业务状态不满足。

断言系统负责判断接口响应是否符合预期。它不只检查 HTTP 状态码，还会覆盖：

- 响应字段存在性。
- 字段类型。
- 业务状态码。
- 错误信息。
- 响应结构。
- 关键业务字段。
- 链路步骤间变量是否正确传递。

最终，规则系统决定“怎么构造输入”，断言系统判断“结果是否正确”。

## Agent 编排系统

ProbeFlow 的主流程采用 Agent Loop，而不是一次性静态 DAG。

核心循环如下：

```text
构建上下文
-> 决策下一步
-> 路由工具
-> 执行工具
-> 收集观察结果
-> 更新任务状态
-> 判断继续或结束
```

编排系统包含以下组件：

- `OrchestrationEngine`：主循环入口，负责驱动任务。
- `TaskStateStore`：保存任务状态、当前阶段、重试信息和失败原因。
- `ContextBuilder`：为当前步骤构造 ContextBundle。
- `Planner`：决定下一步要做什么。
- `ToolRouter`：把 PlanStep 路由到具体工具。
- `ExecutionController`：执行工具，处理并发、超时、重试和失败。
- `ObservationCollector`：把工具输出转成标准化观察结果。
- `LoopDecider`：判断继续、成功结束、失败结束或等待人工输入。

稳定、批量、可并行的子流程后续可以下沉为 DAG 或 Job Flow，例如批量回归、多环境执行、报告汇总等。

## 执行引擎

执行引擎负责真正发起 HTTP 请求并记录事实。它需要支持：

- 请求模板渲染。
- 环境变量注入。
- 鉴权信息注入。
- SUITE 步骤变量提取和传递。
- 串行执行。
- 并发执行。
- 超时控制。
- 重试控制。
- 请求响应脱敏。
- 执行记录持久化。

执行引擎只负责执行，不负责生成测试逻辑，也不负责决定下一步要做什么。执行结果会进入 `ExecutionRecord`，再由 `ObservationCollector` 和失败分析模块转成结构化分析。

## 反馈闭环

ProbeFlow 最重要的价值在于测试结果会反哺系统。

例如：

- 某个 KnowledgeChunk 经常被召回且帮助生成了有效用例，知识库会提升它的权重。
- 某类接口多次出现相同失败，Memory Refinery 会沉淀为 `failure_pattern`。
- 用户经常修改某类断言，系统会沉淀为 `preference` 或 `testing_pattern`。
- 某个 SUITE 经常因为变量提取失败而中断，系统会优化后续 extractRules 生成。
- 某类接口在回归中频繁失败，报告会提高风险等级。

这意味着 ProbeFlow 不是每次任务都从零开始，而是越用越懂项目，越用越懂团队的测试风格。

## 技术方向

当前规划中的后端技术栈：

- Java 21
- Spring Boot
- Maven
- PostgreSQL
- pgvector
- Redis
- Flyway
- JPA
- Docker Compose

推荐的数据存储分工：

- PostgreSQL：核心业务对象、任务状态、用例资产、执行记录。
- pgvector：知识 chunk embedding、长期记忆 embedding。
- Redis：Session Memory、短期状态、任务锁、轻量缓存。
- 文件或对象存储：原始导入文档、源码包、报告附件。

## 当前仓库结构

这个仓库目前是 ProbeFlow 的设计与规划仓库，包含 PRD、设计文档和 Matt skills 本地 issue tracker。

```text
测试Agent设计文档/
├── README.md
├── 测试Agent设计文档/
│   ├── CONTEXT.md
│   ├── V1-FINAL-BOUNDARY.md
│   ├── 数据模型模块设计文档.md
│   ├── 记忆系统设计文档.md
│   ├── 知识库系统设计文档.md
│   ├── 知识库召回与上下文构建设计文档.md
│   ├── 接口自动分析与知识库融合设计文档.md
│   ├── 测试用例生成模块设计文档.md
│   ├── 规则系统设计文档.md
│   ├── 断言系统设计文档.md
│   ├── 接口测试执行引擎设计文档.md
│   ├── 工具编排模板设计文档.md
│   ├── PRD/
│   ├── docs/agents/
│   └── .scratch/
└── .git/
```

注意：当前仓库还不是最终后端工程代码仓库。真正实现时建议在本仓库内或同级目录创建新的后端工程，例如 `probeflow-server`，再按 PRD 和 issue 分阶段实现。

## Matt Skills 开发方式

本仓库已经按 Matt Pocock skills 工作流配置：

- `/to-prd`：把当前讨论整理为 PRD，并发布到本地 issue tracker。
- `/to-issues`：把 PRD 拆成可独立实现的 issue。
- `/implement`：选择某个 issue 后进入实现。
- `/tdd`：用测试驱动方式实现功能。
- `/code-review`：按代码评审方式检查风险、缺陷和测试缺口。

本地 issue tracker 位置：

```text
测试Agent设计文档/.scratch/<feature-slug>/PRD.md
测试Agent设计文档/.scratch/<feature-slug>/issues/<NN>-<slug>.md
```

当前 Phase 1 已经生成：

```text
测试Agent设计文档/.scratch/phase-1-project-scaffold-data-model/PRD.md
测试Agent设计文档/.scratch/phase-1-project-scaffold-data-model/issues/
```

Phase 1 的目标是先建立后端地基：项目骨架、数据库、迁移、核心实体、Repository、基础测试和本地运行环境。它不是最终产品边界，只是实现终极版系统的第一块地基。

## 推荐开发路线

建议按以下顺序推进：

```text
Phase 1: 后端工程骨架 + 数据模型 + 本地基础设施
Phase 2: SourceMaterial / ApiSpec 导入与接口分析
Phase 3: Knowledge Base 文档导入、分块、检索与重排
Phase 4: Memory System 与 Unified Context Builder
Phase 5: SINGLE 测试用例生成
Phase 6: 规则系统与断言系统
Phase 7: HTTP 执行引擎
Phase 8: SUITE 链路测试生成与变量传递
Phase 9: BATCH 回归执行与报告
Phase 10: 反馈闭环、记忆提纯、知识权重调整
Phase 11: 前端控制台与团队协作能力
```

开发时不要从“大而全 Agent”开始写。正确方式是先把领域对象、任务状态、持久化模型和最小执行闭环打牢，然后逐步把知识库、记忆系统、生成系统和执行系统接入。

## 和 AGI-saber-java 的关系

ProbeFlow 不在 `AGI-saber-java` 上直接修改，原因是：

- 目标系统边界已经不同，ProbeFlow 是专门面向 API 测试的 Agent 平台。
- 数据模型需要围绕 Task、ApiSpec、TestCase、ExecutionRecord、Observation、MemoryItem 重新设计。
- 编排方式需要围绕 Agent Loop、工具路由、上下文构建、记忆提纯来搭建。
- 如果在旧项目上改，容易被旧抽象、旧依赖和旧业务边界拖住。

更合理的做法是全新开发 ProbeFlow，然后在需要时参考旧项目或 ClaudeCode-main 中有价值的思想，例如任务状态管理、工具抽象、上下文压缩和执行记录方式。

## 项目愿景

ProbeFlow 的终极目标是成为一个能持续学习的接口测试 Agent：

- 第一次运行时，它能从代码和文档中理解接口。
- 第二次运行时，它能复用上次失败分析得到的经验。
- 多次运行后，它能知道这个项目常见的风险点、团队偏好的断言方式、哪些业务链路最脆弱。
- 当接口变化时，它能提示哪些测试资产可能过期。
- 当回归失败时，它不仅告诉你失败了，还能解释为什么失败、像不像历史问题、该补什么测试。

最终，ProbeFlow 要把“接口测试自动化”升级为“接口测试智能化”：不仅会执行请求，还会理解上下文、维护资产、沉淀经验，并在持续反馈中变得更有用。
