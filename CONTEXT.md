# 测试 Agent — 领域术语表 (Ubiquitous Language)

## V1 产品边界（硬约束）

**V1 仅支持 HTTP API 测试。** 具体边界：

- 测试对象：HTTP/HTTPS REST API
- 分析对象：Spring Boot Controller 代码 / OpenAPI / Swagger 导入
- 用例类型：`caseCategory=API`（`FUNCTIONAL` 为 V2 预留，V1 无生成和执行链路）
- 执行模式：`SINGLE` / `SUITE` / `BATCH`（均为 HTTP 执行组织模式，非测试类型分类）
- 执行协议：HTTP/HTTPS
- 不做：UI 自动化、Service 直调、DB 直连断言、浏览器交互

**SINGLE = 单个 HTTP 接口用例。SUITE = 多个 HTTP 接口按顺序组成的链路用例。BATCH = 多个 HTTP 接口用例批量/并发执行。**

## 核心领域对象

### 输入层
- **SourceMaterial** — 用户提供的输入物料，可能来自 Git 仓库、代码压缩包、OpenAPI/Swagger 文件、需求文档或手工选择

### 资产层
- **ApiSpec** — 接口定义资产，来自代码分析或 OpenAPI/Swagger 导入
- **TestCase** — 正式测试用例资产，归属 ApiSpec（primaryApiSpecId 非空）
- **TestCaseStep** — SUITE 模式的统一步骤定义，每个 step 绑定一个 apiSpecId
- **KnowledgeDocument** — 知识库中的稳定文档资产，代表一份业务文档、接口说明、测试规范或错误码说明
- **KnowledgeDocumentRevision** — KnowledgeDocument 的一次版本快照，用于区分文档更新前后的知识状态
- **KnowledgeChunk** — 从 KnowledgeDocumentRevision 切分出的可检索知识片段，是 RAG 召回、引用溯源和反馈调权的最小单位

### 过程层
- **Task** — 一次生成/确认/执行过程，不拥有 TestCase，产出 Draft + 记录执行引用
- **PlanStep** — 编排执行步骤
- **StepOutcome** — PlanStep 执行后的运行时结果摘要，供编排继续决策，不等同于持久化分析结论
- **TestCaseDraft** — 任务驱动的用例草稿，绑定 Task
- **TaskCaseExecution** — 任务执行的正式 case 引用
- **ExecutionRecord** — 原始执行事实（事实层）
- **Observation** — 对执行结果的结构化分析（分析层），ExecutionRecord 1:N Observation
- **ChangeLog** — 轻量变更备份（前镜像）
- **Report** — 任务最终输出

### 跨层
- **MemoryItem** — 统一记忆抽象，逻辑统一但物理按 Scope 分层存储

## V1 两种 Task 运行模式

**全自动模式**（默认，CI/回归用）：
```
上传代码 → 分析 → 生成 → 自动提升 Draft 为 TestCase → 执行 → 报告
```
- 不等待人工确认，Draft 直接 `PROMOTED`
- `promotionMode = AUTO`

**半自动模式**（首次探索新接口用）：
```
上传代码 → 分析 → 生成 → WAITING_FOR_REVIEW → 用户确认/编辑/丢弃 → 提升 → 执行 → 报告
```
- 在 `CASE_GENERATED` 后暂停等人
- `promotionMode = MANUAL`
- `PENDING_REVIEW` / `manualEdited` / `locked` 在此模式下真正生效

### Task 状态机（V1 定稿）
```
PENDING → ANALYZING → CASE_GENERATED
  ├─ 全自动 → EXECUTING
  └─ 半自动 → WAITING_FOR_REVIEW → EXECUTING
     → ANALYZING_RESULTS → COMPLETED
  任意阶段可触发 → FAILED / CANCELLED
```

### TestCaseDraft 状态
- `PENDING_REVIEW` — 等待用户确认（仅半自动模式）
- `PROMOTED` — 已提升为正式 TestCase
- `DISCARDED` — 用户丢弃
- 用 `promotionMode` 字段（`AUTO` / `MANUAL`）区分提升方式，不增加额外状态

## 知识库 RAG vs 记忆系统 vs 统一上下文

三个概念分属不同层，互补而非替代。

### Knowledge RAG — 管"项目原本写下来的文档知识"
处理对象：业务文档、接口说明、测试规范、错误码说明、环境文档、事故复盘等**人类写好的文档**。

职责：
1. 文档导入、分块、索引
2. 混合检索召回 + Rerank
3. 文档上下文构建
4. 根据检索效果调整权重/authority/rerank

**RAG 是完整的文档检索系统，不会被替代或降级。**

### Memory Refinery — 管"Agent 跑出来的经验"
处理对象：执行结果、Observation、用户修订行为中**沉淀出的运行经验**。

职责：
1. 从原始事实中提纯经验（extract → classify → dedup → merge → compress → tag → embed）
2. 沉淀 failure_pattern、testing_pattern、project_knowledge、preference
3. 经验去重合并与衰减淘汰
4. 长期经验召回

**Memory 是完整的运行经验系统。MemoryRefinery 是所有记忆写入的唯一入口。**

### Unified Context Builder — 把两边拼到一起
读路径核心，按任务阶段从多源组装上下文：
- RAG → 文档知识（business_flow, api_note, test_spec 等）
- Memory → 运行经验（failure_pattern, testing_pattern, preference 等）
- 代码分析 → 接口结构（ApiSpec）
- 当前 Task State → 任务状态

### 反馈信号流向
某些执行结果会同时影响两边，但处理方式不同：
- 某 chunk 经常被召回且有效 → RAG 调整文档权重
- 某类失败在多个任务重复出现 → Memory 沉淀为 failure_pattern
- 用户采纳/拒绝 case → 同时反馈给 RAG（调整 authority）和 Memory（沉淀 preference）
- `MemoryFeedbackBridge`（04-15.4）负责将 RAG 反馈信号桥接到 MemoryRefinery
- chunk 权重/authority/successContribution 是全局统计字段，并发 Task 调整时使用 SQL 原子增量更新（如 `authority = authority + delta`），避免 read-modify-write lost update；V1 不引入分布式锁或消息队列。

## SINGLE vs SUITE 生成路径

两者走不同的生成链路，不共享同一条主链。

### SINGLE 生成路径（单接口驱动）
走现有主链，输入是单个 `ApiSpec`：
```
StructureCaseGenerator → BusinessCaseGenerator → MemoryCaseEnhancer
  → AssertionSuggestionGenerator → CaseNormalizer
```
规则系统（05）天然适合单接口的字段约束覆盖，三层串行链均为单 ApiSpec 驱动。

### SUITE 生成路径（跨接口链路，独立链路）
采用 **"先有 SINGLE，后有 SUITE"** 策略：

1. **打底**：对链路涉及的每个 ApiSpec，先生成可用的 SINGLE case（至少各一条正常请求）
2. **链路识别**：从 `business_flow` 知识库文档 / 接口命名语义 / DTO 关联中识别步骤顺序和变量依赖
3. **组装 steps[]**：复用对应 SINGLE case 的 requestTemplate 作为每步模板，补 `extractRules`、`${suite.xxx}` 变量引用、`critical` 标记
4. **链路增强**：AI 补链路级 case（正常链路、token 缺失、前步失败影响、变量提取失败等）

### V1 SUITE 来源限制
只支持两类来源，不做全项目自动链路发现：
1. 用户显式选择多个接口并指定顺序
2. 知识库 `business_flow` 文档明确给出链路定义

## ExtractionRules 生成责任

**谁生成 `extractRules`？** 这是 SUITE 模式的核心依赖——没有 extractRules，链路变量无法在步骤间传递。

### 责任分配
- **SINGLE case**：`extractRules` 默认空。单接口执行不需要向下游传递变量。
- **SUITE case**：由 `SuiteCaseGenerationPipeline` 中的 **DependencyLinker** 子组件生成。它负责识别"哪个步骤产出什么变量、哪个步骤消费什么变量"，同时产出 `extractRules` 和 `inputData` 中的 `${suite.xxx}` 变量引用。
- **半自动模式**：用户可查看和修改 extractRules（改 sourcePath、targetScope、targetKey、增删规则）。
- **代码分析（04）** 只提供结构线索（DTO 字段名、返回体结构、鉴权头命名），**不直接生成** extractRules。
- **规则系统（05）** 不负责生成 extractRules——它关注的是单步输入约束变异，不处理步骤间数据流。
- **执行引擎（08）** 只消费 extractRules，不决策生成。

### extractRules 和变量引用必须一起生成
`steps[i].extractRules` 和 `steps[i+1].inputData` 中的 `${suite.xxx}` 是成对的——生产者抽什么、消费者用什么，必须由同一个组件同时产出，不能拆开生成。

## 任务创建与回归执行

### Task 创建是编排系统的前置步骤
Task 创建不是 Agent Loop 中的一步，而是 Loop 启动前的初始化。由一个独立的 **TaskInitializationService**（TaskFactory）负责：

```
前端/API 请求 → TaskInitializationService → Task 持久化 → OrchestrationEngine.run(taskId)
```

职责：
- 创建探索任务（`taskType=API_TEST`，含代码导入/分析）
- 创建回归任务（`taskType=REGRESSION`，基于已有 TestCase 筛选）
- 补齐 `targetApiSpecIds`
- 初始化待执行 case 列表（`TaskCaseExecution`）
- 补齐任务元数据

### 回归任务创建流程
```
用户按 primaryApiSpecId / tags / scenarioName / moduleName 筛选已有 TestCase
  → 勾选一批 TestCase
  → TaskInitializationService 反查 primaryApiSpecId 并去重 → 填入 targetApiSpecIds
  → 创建 Task(taskType=REGRESSION, sourceType=MANUAL)
  → 写入 TaskCaseExecution 待执行项
  → OrchestrationEngine.run(taskId)
```

`sourceType=MANUAL` 的含义：任务来源不是重新导入代码/OpenAPI，而是用户基于已有测试资产发起的一次运行。

### 回归任务的编排路径
Planner 识别到 `taskType=REGRESSION` 后跳过接口分析和用例生成，直接进入执行链路：
```
PrepareExecutionInput → ExecuteBatch → AnalyzeResults → GenerateReport
```
不产出 `ANALYZE_CODE_API`、`GENERATE_CASES` 步骤。

`PrepareExecutionInput`：执行前补齐环境变量、鉴权信息、执行配置（不是分析/生成，是执行前准备）。

## SUITE TestCaseStep 是快照，不是引用

### 快照冻结原则
**`SUITE TestCase.steps[]` 存储的是组装生成时的步骤快照，不是对 `SINGLE TestCase` 的运行时引用。** 这是 feature，不是 bug。

`requestTemplate`、`assertionDefinitions`、`extractRules` 都在组装生成时写入，保存确认后与来源 SINGLE case 脱离关系。

### 为何冻结是正确的
1. SUITE 本身是"已验证的链路配方"——链路资产必须稳定，不能被下游更新偷偷改行为
2. 回归测试最怕隐式漂移——同一条 SUITE 今天和昨天行为不同，失败原因无法归因
3. 资产独立性 > "自动保持最新"

### 不自动同步
后续 SINGLE TestCase、ApiSpec、规则模板、断言模板的变化，**不会自动更新**已有 SUITE TestCase。

### 显式刷新
如需更新 SUITE，必须显式重新触发 `SuiteCaseGenerationPipeline`，生成新的 SUITE 草稿，由用户选择替换或保留旧版本。`ChangeLog` 保证刷新前有前镜像备份，可回滚。

### 过时检测（轻量提示，V1 可选）
SUITE TestCase 可记录 `basedOnApiSpecVersions`、`generatedFromSingleCaseIds`、`generatedAt`。当底层 ApiSpec.version 变化或来源 SINGLE case 版本变化时，标记 `staleStatus = STALE`，仅提示用户刷新，不自动修改。

## StepOutcome vs Observation —— 两个不同层的对象

01-PRD 和 09-PRD 各自定义了一个叫 "Observation" 的对象，但字段模型和用途完全不同。V1 必须将它们拆分为两个不同的概念。

### StepOutcome（编排运行时，09 所属）
- **定位**：PlanStep 执行完后的即时总结，内存态，供 Planner/LoopDecider 决策
- **生命周期**：主循环内部，不直接持久化为业务分析记录
- **核心字段**：`stepId`, `taskId`, `stepType`, `status`, `summary`, `executionId?`, `riskHints[]`, `failureHints[]`, `memoryCandidateHints[]`
- **回答的问题**："这一步执行完发生了什么，下一步应该做什么"

### Observation（分析持久层，01 所属）
- **定位**：绑定 ExecutionRecord 的结构化分析结论，持久态，进入 PG / 报告 / 记忆系统
- **生命周期**：持久化，供查询、审计、记忆提纯消费
- **核心字段**：`observationId`, `taskId`, `executionId`, `sourceStepId?`, `observationType`, `summary`, `failureReason`, `riskLevel`, `nextSuggestion`, `source`, `createdAt`
- **回答的问题**："针对某次执行结果，形成了什么分析结论"

### 两者关系
```
PlanStep 执行 → StepOutcome（运行时）
  → 若需进一步分析（如 ANALYZE_FAILURE 步骤）
    → Observation（持久化，1 条 ExecutionRecord 可挂 N 条 Observation）
```
不是平级对象。不是所有 StepOutcome 都会转化为 Observation（如 ANALYZE_CODE_API 的 outcome 通常不产生 Observation）。

## 代码分析 readiness gate —— 防止时序竞争

### 问题
04-接口分析采用"同步首屏 + 后台异步增强"模式。同步返回（<5s）仅含路由和基础参数，DTO 展开、校验注解、鉴权推断在后台异步完成。Agent Loop 可能在异步增强完成前就进入 GENERATE_CASES 或 EXECUTE_*——此时 ApiSpec 还是半成品。

### 方案：readiness gate（增强版 B）
不选纯阻塞 A（打掉异步设计价值），也不选裸 `PARTIAL/FULL` 两态（太粗）。

**ApiSpec 增加 readiness 标志位：**
- `routeReady` — 路由提取完成
- `basicParamReady` — 基础参数结构完成
- `dtoExpanded` — DTO 递归展开完成
- `validationReady` — 校验注解全量提取完成
- `authReady` — 鉴权推断完成
- `knowledgeContextReady` — 知识库业务上下文召回完成

**每个 PlanStepType 声明最低依赖：**
- `RETRIEVE_KNOWLEDGE`：`routeReady` 即可
- `GENERATE_CASES`：`dtoExpanded + validationReady + authReady`
- `EXECUTE_*`：`caseReady + authReady`

**Planner 决策时检查 readiness gate，不满足 → `WAIT_ANALYSIS_COMPLETION` 或 REPLAN 到不依赖 FULL 的步骤。**

**V1 硬规则：PARTIAL 状态下不生成正式 TestCaseDraft。** PARTIAL 仅用于 UI 首屏展示和候选接口浏览。FULL 之后才允许进入生成和执行。staleStatus 是兜底机制，不是主路径设计。

## ApiSpec 版本变化 → TestCase 过时检测（V1 必做）

### 问题
ApiSpec 重新分析后 `version` 递增，但已有 TestCase 不知道自己基于哪个版本生成。执行时可能缺少新增必填字段、断言基于旧边界值——失败原因被错误归因，资产静默腐烂。

### TestCase 增加字段
- `basedOnApiSpecVersion` — 生成时的 ApiSpec.version
- `basedOnApiSpecFingerprint` — 生成时关键结构的轻量摘要（path + method + DTO 字段树 + constraints + auth），用于判断是否有实质影响（version 变了但 fingerprint 不变 = 无实质影响）
- `staleStatus`：`FRESH` / `OUTDATED`（version 变了但未确认实质影响）/ `STALE_CONFIRMED`（fingerprint 变了，确定受影响）
- `staleReasonSummary` — 过时原因摘要

### SUITE 额外处理
SUITE 的每个 TestCaseStep 也需记录 `basedOnApiSpecVersion` + `basedOnApiSpecFingerprint`——SUITE 涉及多个 apiSpecId，不能只看 primaryApiSpecId。

### 回归任务创建时的检查
系统检查所有选中 case 的 staleStatus。如有 `OUTDATED` 或 `STALE_CONFIRMED`，提示用户：
- 继续执行（忽略，用旧 case）
- 生成新 Draft（重新生成，来源标记 `REGEN_FROM_STALE`）
- 查看差异（ApiSpec 新旧版本 constraints 对比）

### V1 原则
**检测要强，自动覆盖要弱。** 不自动覆盖正式 TestCase。重新生成走 Draft → 用户确认 → Promotion → ChangeLog，与半自动模式流程一致。staleStatus 和 fingerprint 让系统可感知变化，但替换由用户决策。

## MemoryRefinery 是异步后台任务，不是编排步骤

### 问题
02-PRD 将 MemoryRefinery 描述为"后台异步提纯，不阻塞主流程"。但 09-PRD 将 `REFINE_MEMORY` 列为 PlanStepType，与其他步骤平级放在 Agent Loop 中。两者冲突。

### 修正
- **删除 `REFINE_MEMORY` StepType。** 提纯不由 Planner 决策，不由 Agent Loop 执行。
- **提纯改为事件驱动的异步后台任务**，由 `MemoryRefinementScheduler` 负责。
- **同步主循环只做轻量操作**：`writeTaskMemory`（同步写 PG）、`enqueueMemoryCandidate`（入队候选）。

### 触发时机（三者共存，但候选只被提纯一次）
1. **回合后触发**：候选达阈值 → 异步触发一次提纯（节流：上次未完成则 stash，跑完后只做一次 trailing）
2. **阶段结束触发**：一批接口跑完 → 合并本阶段候选，批量提纯
3. **任务结束触发**：Task → COMPLETED/FAILED → 强制 flush 剩余候选

每次提纯只处理 `status = PENDING` 的候选，已提纯（`REFINED`）或已丢弃（`DROPPED`）的不重复处理。

### 编排层感知（不控制）
TaskState 可展示提纯进度（`memoryRefinementStatus`、`pendingMemoryCandidates`、`lastRefinedAt`），供报告层和用户了解状态，但 Planner 不调度提纯。

## 编排模式：模板驱动 + 按需 Planner

### 问题
09-PRD 将 Agent Loop + AI Planner 作为主编排模型——每轮都让 Planner 决策下一步。但探索/回归任务的正常路径是高度预定的（先分析才能生成、先生成才能执行），Planner 在 80-90% 的场景中没有真正的开放式选择空间。每轮都调 LLM 决策浪费 token、增加延迟、降低确定性。

### 不是推倒 Agent Loop，而是收敛 Planner 的职责
V1 采用 **template-first 编排**：

- **TaskTemplateRegistry**：按 `taskType` 提供默认流程模板（硬编码或 YAML 配置）
  - `API_TEST`：`ANALYZE_CODE_API → RETRIEVE_KNOWLEDGE → GENERATE_CASES → [WAIT_FOR_REVIEW?] → EXECUTE → ANALYZE_RESULTS → GENERATE_REPORT`
  - `REGRESSION`：`PREPARE_EXECUTION_INPUT → EXECUTE_BATCH → ANALYZE_RESULTS → GENERATE_REPORT`
- **TemplateExecutor**：按模板顺序推进步骤。正常路径下 Planner 不介入。
- **Planner 只在三类场景被唤醒**：
  1. **分叉决策**：执行模式选 SINGLE/SUITE/BATCH、是否需要补知识库召回、stale case 是否先生成
  2. **异常决策**：执行失败后重试/分析/跳过/终止、结果与预期大幅偏离时是否补测
  3. **半自动恢复后重规划**：用户修改了 Draft、调整了 SUITE steps 后模板是否还适用
- **LoopDecider**：判断正常继续 / 进入等待 / 触发异常分支 / 请求 Planner 重规划 / 任务结束

这不是 DAG 引擎，也不是纯 Agent Loop。是 **模板驱动 + 异常时 AI 重规划** 的混合模型——正常路径保证确定性和效率，异常路径保留灵活性。

## expectedStatusCode 的唯一权威来源

### 问题
05-规则系统、06-TestCaseDraft、07-SystemAssertionRuleRegistry 三处各自维护了 caseType → expectedStatusCode 映射。BOUNDARY_VALUE 这种 case（min→200, max+1→400）实例不同期望不同，按 caseType 映射天然无法表达实例差异。

### 修正
**expectedStatusCode 的唯一权威来源是 TestCase/TestCaseDraft 实例字段本身。** 生成时由 05/06 写入，执行时 07 读取。

- **05/06 负责写入**：规则系统、AI 生成器、SUITE 组装器在产出 case 时必须写实 `expectedStatusCode`，不能留空让下游猜
- **07 删除 caseType → statusCode 映射**：SystemAssertionRuleRegistry 不再维护此映射，不再根据 caseType 推导状态码
- **07 改为读取 expectedStatusCode**：AssertionPlanBuilder 如果发现 `expectedStatusCode != null` 且 assertionDefinitions 中没有同类状态码断言，则据此生成 STATUS_CODE CRITICAL 断言。如果用户已手工写同类断言，用户优先
- **07 Registry 保留的职责**：补通用断言（Content-Type、响应时间）、severity 策略、去重规则、断言冲突处理

### 不是 07 完全不管状态码断言
而是从"根据 caseType 猜状态码"改为"根据 case 实例上的 expectedStatusCode 生成状态码断言"。语义源只剩一处——case 自己说了算。

## 失败归因：基础自动 + 深度按需

### 问题
当前 `ANALYZE_FAILURE` 是 Planner 可选选择的独立步骤。但 EXECUTE_* 完成后已有 assertionResults 和 failureHints。如果 Planner 没有触发 ANALYZE_FAILURE，失败执行就只留下原始断言结果，没有归因，记忆系统和 RAG 反馈闭环都没法消费。

### 拆成两层

**1. 基础归因 — 执行后自动触发（必做）**
每次 EXECUTE_* 完成后，如果 AssertionSummary.overallStatus = FAILED 或 PASSED_WITH_WARNINGS，自动产出一条基础 Observation：
- 归因类别初判：`TEST_ASSET_ISSUE`（case 本身有误）/ `SYSTEM_BUG`（真实 bug）/ `ENVIRONMENT_ISSUE`（Token 过期、网络超时等）/ `UNCERTAIN`
- 不调 LLM、不查知识库，基于 assertionResults + HTTP status + response body 做规则推断
- `analysisLevel = BASIC`

**2. 深度失败分析 — Planner 按需触发（可选）**
`ANALYZE_FAILURE` 保留，但收缩为**深度分析步骤**。处理复杂问题：
- 为什么这次和上次行为不同
- 接口契约是否变了
- 是否需要补测/重新生成 case
- 调 LLM、查知识库、比历史记录
- `analysisLevel = DEEP`

### Planner 的决策链
Planner 面对的不再是裸 failureHints，而是 StepOutcome + BaseObservation。据此决定：
- `RETRY`（环境问题）
- `ANALYZE_FAILURE`（不确定或疑似 bug，需要深挖）
- `REPLAN`（测试资产问题，调生成链路）
- `FINISH_FAIL`

### 记忆系统与 RAG 的稳定输入
基础 Observation 保证下游至少拿到结构化归因。深度 Observation 可在后续补充或覆盖基础结论。

## 知识库文档 Revision 机制

### 问题
04-PRD 定义了文档 frontmatter 含 `version` 和 `updatedAt`，但未讨论文档更新后旧 chunk、旧 embedding、旧索引如何处理。如果 RAG 检索不区分版本，新旧 chunk 可能同时出现在 ContextBundle 中，上下文自相矛盾。

### 核心方案：文档级 revision + 检索过滤旧版本
**数据模型**：
```
KnowledgeDocument (稳定的 documentId, docType, title)
  └─ KnowledgeDocumentRevision (docRevisionId, documentId, version, updatedAt, isLatest, revisionStatus)
       └─ KnowledgeChunk (chunkId, documentId, docRevisionId, chunkStatus, content, embedding)
```
chunk 归属于 revision，不直接归属于 document。历史引用可精准定位到: 哪份文档 → 哪个 revision → 哪个 chunk。

**更新行为**：
- 文档更新产生新 revision（新 docRevisionId），`isLatest = true`，旧 revision → `isLatest = false`
- 旧 revision 的所有 chunk → `chunkStatus = SUPERSEDED`
- 旧 chunk 不物理删除——历史 Observation/Report 仍需引用它们作为审计证据

**检索过滤**：
- 默认检索只召回 `chunkStatus = ACTIVE`（即最新 revision）的 chunk
- 同 documentId 只保留最新 revision 的结果
- 旧 revision 的 chunk 可被显式回溯查询，但不参与默认 RAG 检索

### 与 TestCase stale 机制的差异
- 文档是知识源，更新后**自动切换**到新 revision（无需人工审批）。不像 TestCase 那样有 manualEdited/locked 保护
- 高 authority 文档或大量被引用的文档更新时可进入 `reviewSuggested` 标记（仅治理提醒，不阻塞检索），V1 可先留模型位

### V1 最小必做
1. `documentId + docRevisionId` 双层 ID
2. 文档更新 → 新 revision → 旧 chunk SUPERSEDED
3. 检索默认过滤 SUPERSEDED chunk
4. 历史引用保留对旧 chunk 的追溯能力

## SourceMaterial — 输入物料统一入口

### 问题
Task 有 `sourceType + sourceRef` 但不足以表达：物料是文件/目录/Git URL/知识库文档、是否已就绪、存储位置是什么、是否有多个输入源。当前 04、06、09 各说各的，代码获取和分析入口边界模糊。

### 轻量 SourceMaterial 模型
```text
SourceMaterial
- materialId
- taskId
- materialType: CODE_ARCHIVE / GIT_REPO / OPENAPI_FILE / REQUIREMENT_DOC / MANUAL_SELECTION
- originalName / originalRef
- storagePath
- ingestStatus: PENDING / READY / FAILED
```
Task 保留 `sourceType`（决定模板走向），`SourceMaterial[]` 描述实际输入物料集合。支持多输入源（如同时上传代码 + OpenAPI + PRD）。

### 谁负责获取代码
- **SourceIngestionService**：接收上传文件/Git URL → clone/解压/存储 → 生成 SourceMaterial → 标记 READY
- **ANALYZE_CODE_API**：只分析已就绪的代码目录，不负责 clone、不负责解压
- **知识库导入**：REQUIREMENT_DOC 在 SourceIngestionService 中就导入知识库

### 主入口约束
- **V1 支持作为主入口**：`CODE_REPO` / `OPENAPI` / `MANUAL`
- **V1 作为辅助输入**：`REQUIREMENT_DOC`（知识增强、业务上下文，不独立撑起完整主流程）
- `REQUIREMENT_DOC` 在 V1 不适合作为独立 Task.sourceType——没有代码或 OpenAPI 就没有稳定的 ApiSpec 来源

### 初始化阶段
```
CreateTaskRequest
  → SourceIngestionService (clone/解压/导入)
  → SourceMaterial[] ready
  → TaskInitializationService (创建 Task + 补齐 targetApiSpecIds)
  → OrchestrationEngine.run(taskId)
```

## 报告系统 — 结构化聚合器，不是独立智能分析器

### 定位
V1 报告系统是 **Task 级别的执行事实、分析结论和任务状态的统一结构化汇总**，加一层轻量自然语言总结。不独立产生新事实。

### 数据来源
- `Task` / `TaskState` — 任务类型、来源、是否半自动、是否中途失败
- `ExecutionRecord[]` → 执行事实：caseCount, passCount, failCount, warningCount
- `Observation[]` → findings（摘取 FAILURE_ANALYSIS 和 RISK_EVALUATION 类 Observation）
- `Observation.nextSuggestion` + 固定规则 → suggestions（记忆系统可增强，但不作为主依赖）

### findings 结构
每条 finding 回链到证据：
```json
{
  "findingId": "F-001",
  "observationId": "OBS-123",
  "severity": "HIGH",
  "category": "SYSTEM_BUG / TEST_ASSET_ISSUE / ENVIRONMENT_ISSUE / UNCERTAIN",
  "summary": "...",
  "affectedCaseId": "TC-001",
  "affectedApiSpecId": "API-ORDER-CREATE",
  "evidenceRefs": ["EXEC-001"]
}
```

### suggestions 结构
面向用户，类型限制为：`FIX_TESTCASE` / `RETRY_EXECUTION` / `REGENERATE_CASE` / `CHECK_ENVIRONMENT` / `ADD_REGRESSION_CASE`。不暴露 `MEMORY_REFINE` 等内部系统动作。

### summary 生成
**数字和结论骨架由代码生成，AI 只负责语言表达，不负责事实判断。** 先模板化摘要（测了多少条、通过/失败/警告数、主要失败分布、是否存在高风险 finding），再可选让 AI 润色成自然语言。

### 失败任务
Task FAILED 时同样生成报告，标记 `reportStatus = PARTIAL`，明确写出失败终止点和未完成范围。

### 输出格式
V1 仅输出 JSON（API 返回，前端渲染）。不做 PDF/Markdown 导出。

## SUITE Token 过期处理

SUITE 适合短链路、短耗时、Token TTL 覆盖整个链路的场景。V1 不做自动 Token 刷新。

- 遇到 401 且请求使用了 `${suite.token}` 或其他前序步骤提取的认证变量 → 基础归因 `ENVIRONMENT_ISSUE`，细分原因"疑似 Token 过期"
- **不重试当前 step**：同一个过期 token 重试大概率无效
- 动作：终止当前 SUITE，报告建议拆分长链路、提高测试 Token TTL、或重新从 login 开始执行整条 SUITE
- 自动重新执行 login step 是 V2 的 AuthSession/TokenProvider 能力，不放进 V1。AuthInjector 只负责注入已有 token，不反向调度步骤

## Business 与 Memory 生成重叠处理

CoverageKey（`dimension + targetField`）不适合表达业务规则、历史失败等语义型 case。V1 接受少量重叠，采用低成本防重：

- **MemoryCaseEnhancer prompt 软约束**：传入已生成 Draft 的标题、caseType、expectedStatusCode、normalized input 摘要，要求只补充未覆盖的历史失败或经验模式。不是语义去重系统，只是减少明显重复。
- **CaseNormalizer 确定性去重**：对所有来源的 case 统一 canonicalize request/inputPatch，按 `dedupKey = caseType + targetField + normalizedInputPatch + expectedStatusCode` 去重。
- **不做 LLM 语义去重**（已在 05-PRD 10.4 节明确）。V1 case 总量有限，重复一两条的代价只是多执行一次。

## 最小可行路径与环境依赖

### 只给 Git URL 不等于可执行
Git URL → clone → 分析 → 生成 → 自动提升 TestCase。这条链路是完整的。但 EXECUTE_* 需要额外的运行时配置：`baseUrl`、目标环境服务可达、鉴权凭证（Token/API Key/账号）、必要的外部依赖。

**执行前 readiness gate**：EXECUTE_* 步骤检查 `caseReady + baseUrlReady + environmentReady + authCredentialReady`。不满足时：
- 不直接 FAILED（会丢失已完成的接口识别和用例生成成果）
- 不硬跑（会产出一堆 401/连接失败，归因污染的噪音）
- 任务进入 `COMPLETED_WITH_BLOCKERS`：已完成接口识别和用例生成，执行阶段因缺运行环境/鉴权配置被跳过
- 报告输出已完成的 Assets（ApiSpec + TestCase 列表）+ 阻塞项清单

### 多模块仓库
一个 Git repo 可能有多个 Spring Boot app。V1 默认策略：自动选择唯一 Spring Boot 启动模块。如果检测到多个候选模块，任务进入 `WAITING_FOR_CONFIG`，提示用户选择目标模块。

### V1 最小闭环
"只给 Git URL"的最小可行闭环是：**生成可执行测试资产（ApiSpec + TestCase）+ 部分报告**。真实 HTTP 执行必须依赖额外环境配置——除非 V2 补自动启动/部署能力。
