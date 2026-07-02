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

知识库系统保存的是“项目原本就知道的东西”。它面向人类文档，例如：

- PRD
- 业务流程文档
- 接口说明
- 测试规范
- 错误码说明
- FAQ
- 环境说明
- 事故复盘

知识库不是简单把文档丢进向量库，而是按以下流程治理：

```text
Raw Documents
-> KnowledgeIngestService
-> KnowledgeChunker
-> KnowledgeMetadataExtractor
-> KnowledgeIndex
-> KnowledgeRetriever
-> KnowledgeReranker
-> KnowledgeContextBuilder
```

它会给文档补充结构化元数据，例如系统名、模块名、文档类型、业务实体、适用阶段、权威等级、更新时间等。召回时同时使用结构化过滤、标签过滤、语义召回和重排，而不是只靠 embedding 相似度。

知识库主要解决的是：

- 这个接口属于哪个业务流程。
- 这个接口有哪些业务前置条件。
- 哪些字段有业务约束。
- 哪些错误码需要重点断言。
- 哪些测试规范必须遵守。
- 失败分析时应该参考哪些历史事故或规则。

## 记忆系统

记忆系统保存的是“Agent 后来学到的东西”。它和知识库是平级关系，不互相替代。

一句话区分：

```text
知识库回答：项目本来写下了什么？
记忆系统回答：Agent 在运行中学会了什么？
```

ProbeFlow 的记忆系统分为五层：

- `Session Memory`：当前会话的短期上下文。
- `Task Memory`：当前任务中的持续状态和执行事实。
- `Memory Refinery`：记忆提纯层，负责抽取、分类、去重、合并和压缩。
- `Refined Long-term Memory`：跨任务可复用的长期经验。
- `Unified Context Builder`：按任务阶段召回并组装上下文。

可选还会有 `Team Knowledge Memory`，用于承载团队人工维护的规范、项目说明和复盘材料。

### Session Memory

Session Memory 保存当前会话内的短期信息，例如当前目标、最近几轮结论、最近工具调用摘要、最近一次失败原因和当前阶段的中间决策。它生命周期短、更新频繁，适合放在 Redis 或本地缓存中，并使用 TTL 自动过期。

### Task Memory

Task Memory 保存当前任务的结构化事实，例如需求摘要、接口摘要、已生成用例、执行记录、断言结果、失败样本、分析结论和下一步建议。它服务于任务恢复、断点续跑和多轮 Agent Loop。

### Memory Refinery

Memory Refinery 是所有长期记忆写入的入口。原始对话、工具输出和错误日志不会直接进入长期记忆，而是先经过：

```text
extract -> classify -> deduplicate -> merge -> compress -> tag -> embed
```

适合沉淀成长期记忆的信息包括：

- 某类接口常见失败模式。
- 某项目特殊鉴权规则。
- 某业务领域的边界测试策略。
- 某类接口常用断言模板。
- 用户偏好的测试风格和报告格式。

不适合直接沉淀的信息包括一次性日志全文、临时推理过程、低价值工具输出和无法泛化的对话片段。

### Long-term Memory

长期记忆存储跨任务仍然有价值的经验，典型类型包括：

- `project_knowledge`
- `testing_pattern`
- `failure_pattern`
- `preference`
- `domain_rule`

长期记忆建议使用 PostgreSQL + pgvector 存储，同时支持结构化过滤和向量召回。每条记忆需要记录类型、标题、摘要、适用范围、来源任务、触发条件、标签、重要性和最近使用时间。

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

不同阶段需要不同上下文。例如：

- 生成测试用例时，需要接口结构、字段约束、业务规则、历史测试模式。
- 执行接口测试时，需要环境变量、鉴权方式、请求模板、变量依赖。
- 分析失败时，需要原始请求响应、断言结果、历史失败模式、相关文档说明。
- 生成报告时，需要任务目标、执行摘要、失败分类、风险建议和可复用结论。

这个模块避免把所有历史信息一股脑塞给模型，而是按阶段、按目标、按预算构造刚好够用的上下文。

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
