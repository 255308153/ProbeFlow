# 01-数据模型与核心对象 PRD（v3 — 领域建模最终确认稿）

## 1. 模块定位

本文档定义测试 Agent / 测试平台第一版的核心数据对象，是所有模块的共同语言。

## 2. 核心设计决策

### 2.1 两层对象：资产层 + 过程层

```
┌─────────────────────────────────────────┐
│ 资产层（持久、可维护、可复用）              │
│                                         │
│  ApiSpec ── 接口定义资产                 │
│  TestCase ── 正式测试用例资产             │
│    └─ TestCaseStep ── 统一步骤定义        │
│                                         │
├─────────────────────────────────────────┤
│ 过程层（单次任务产物）                    │
│                                         │
│  Task ── 一次生成/确认/执行过程           │
│    ├─ PlanStep ── 编排执行步骤            │
│    ├─ TestCaseDraft ── 任务驱动草稿       │
│    ├─ TaskCaseExecution ── 执行引用       │
│    ├─ ExecutionRecord ── 原始执行事实      │
│    │    └─ Observation ── 分析层结果       │
│    ├─ ChangeLog ── 轻量变更备份           │
│    └─ Report ── 任务最终输出              │
│                                         │
│  MemoryItem ── 统一记忆抽象（跨层）        │
└─────────────────────────────────────────┘
```

### 2.2 关键决策

| 决策 | 说明 |
|------|------|
| `TestCase` 不归属 `Task` | `Task` 是一次性过程，`TestCase` 是长期资产。归属 `ApiSpec` |
| **不建 `TestPlan`** | V1 链路不需要分组容器，用 `apiSpecId` + `tags` 筛选足够 |
| `TestCaseDraft` 绑定 `Task` | 生成是任务驱动的，draft 有 taskId |
| `primaryApiSpecId` 非空 | SINGLE 时是主接口，SUITE 时是链路入口/第一步接口 |
| `mode=SUITE` 走 `steps[]` | 每个 step 含 `apiSpecId`，跨接口链路通过 steps 承载 |
| V1 不做 TestCaseVersion | 用 ChangeLog 做前镜像备份 |
| 生成引用和执行引用拆开 | `TestCaseDraft` 存生成产物，`TaskCaseExecution` 存执行引用 |
| 未来回归用标签和筛选 | `apiSpecId` + `tags` + `scenarioName` + `moduleName` 替代 TestPlan |

### 2.3 为什么不建 TestPlan

V1 核心链路是：

```
上传/导入 → 分析 → 生成 → 确认 → 执行 → 报告
```

`TestPlan` 在这个链路中没有不可替代的增量价值。它能做的是"回头回归时作为分组快捷入口"——但 V1 这个场景本身就靠 `apiSpecId` + `tags` 筛选就能搞定。V2 如果出现真正的跨接口长期资产编排需求，再引入 TestPlan。

### 2.4 事实与分析分层

- **事实层**（ExecutionRecord）：记录"发生了什么"
- **分析层**（Observation）：记录"怎么看待这次执行"

同 ExecutionRecord 可挂多条 Observation，关系为 1:N。

### 2.5 逻辑统一，物理分层存储（MemoryItem）

MemoryItem 逻辑统一，物理存储按 Scope 分三张表：Session → Redis TTL，Task → PG 单独表，Long-term → PG + pgvector。

### 2.6 TestCase 主表 + 类型扩展

API/Functional 共享主表，类型差异字段通过 `detail`（JSONB）+ `detailType` 区分。SUITE 模式通过 `steps[]` 承载跨接口链路。

## 3. 核心对象总览

| 对象 | 定位 | 层 | 存储 |
|------|------|:--:|------|
| `ApiSpec` | 接口定义资产 | 资产层 | PostgreSQL |
| `TestCase` | 正式测试用例资产 | 资产层 | PostgreSQL |
| `TestCaseStep` | SUITE 模式统一步骤定义 | 资产层 | 内嵌于 TestCase.steps |
| `Task` | 一次生成/确认/执行过程 | 过程层 | PostgreSQL |
| `PlanStep` | 编排执行步骤 | 过程层 | PostgreSQL |
| `TestCaseDraft` | 任务驱动的用例草稿 | 过程层 | PostgreSQL |
| `TaskCaseExecution` | 任务执行的正式 case 引用 | 过程层 | PostgreSQL |
| `ExecutionRecord` | 原始执行事实 | 过程层 | PostgreSQL |
| `Observation` | 对执行结果的结构化分析 | 过程层 | PostgreSQL |
| `ChangeLog` | 轻量变更备份（前镜像） | 过程层 | PostgreSQL |
| `MemoryItem` | 统一记忆抽象 | 跨层 | Redis / PG / PG+pgvector |
| `Report` | 任务最终输出结果 | 过程层 | PostgreSQL |

## 4. 资产层对象

### 4.1 ApiSpec

接口定义资产，来自代码分析 / OpenAPI / Swagger 导入。

| 字段 | 类型 | 说明 |
|------|------|------|
| `apiSpecId` | String (UUID) | 主键 |
| `system` | String | 所属系统 |
| `module` | String | 所属模块 |
| `httpMethod` | String | GET / POST / PUT / DELETE / PATCH |
| `path` | String | 接口路径 |
| `summary` | String | 接口摘要 |
| `parameters` | JSONB | 参数树（含 DTO 展开结果） |
| `constraints` | JSONB | 字段约束信息（FieldConstraint[]） |
| `auth` | JSONB | 鉴权配置（AuthInfo） |
| `sourceType` | Enum | `CODE_ANALYSIS` / `OPENAPI` / `SWAGGER` / `MANUAL` |
| `sourceRef` | String | 来源引用 |
| `version` | Integer | 分析版本号（重新分析时递增） |
| `createdAt` | DateTime | 创建时间 |
| `updatedAt` | DateTime | 更新时间 |

### 4.2 TestCase

正式持久测试资产，归属 ApiSpec。SINGLE 模式对应一个 ApiSpec，SUITE 模式通过 steps[] 承载跨接口链路。

| 字段 | 类型 | 说明 |
|------|------|------|
| `caseId` | String (UUID) | 主键 |
| `primaryApiSpecId` | String (UUID) | 外键 → ApiSpec，**非空**。SINGLE=主接口，SUITE=入口/第一步接口 |
| `caseCategory` | Enum | `API` / `FUNCTIONAL` |
| `mode` | Enum | `SINGLE` / `SUITE` |
| `title` | String | 用例标题（格式：`{接口名}-{场景类型}-{关键变化点}`） |
| `description` | String | 用例描述 |
| `preconditions` | String[] | 前置条件列表 |
| `expectedResult` | String | 预期结果描述 |
| `priority` | Enum | `LOW` / `MEDIUM` / `HIGH` |
| `riskLevel` | Enum | `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `tags` | String[] | 标签，如 `["payment", "auth", "order"]` |
| `scenarioName` | String | 场景名称（如"创建订单链路"），用于分组筛选 |
| `moduleName` | String | 模块名，用于分组筛选 |
| `status` | Enum | `DRAFT` / `READY` / `ARCHIVED` |
| `source` | Enum | `STRUCTURE` / `BUSINESS` / `MEMORY` / `MANUAL` |
| `manualEdited` | Boolean | 是否人工修改过（默认 false） |
| `locked` | Boolean | 是否锁定不允许自动覆盖（默认 false） |
| `detailType` | Enum | `API` / `FUNCTIONAL` |
| `detail` | JSONB | SINGLE 时存 ApiTestCaseDetail / FunctionalTestCaseDetail |
| `steps` | JSONB | SUITE 时存 TestCaseStep[] |
| `createdAt` | DateTime | 创建时间 |
| `updatedAt` | DateTime | 更新时间 |
| `updatedBy` | String | 最后修改人 |

**分组/回归策略（V1 无 TestPlan 的替代方案）：** 按 `primaryApiSpecId` + `tags` + `scenarioName` + `moduleName` 组合筛选。例如 `SELECT * FROM test_case WHERE primary_api_spec_id IN (...) AND tags @> ARRAY['regression']`。

### 4.3 TestCaseStep

SUITE 模式的统一步骤定义。

| 字段 | 类型 | 说明 |
|------|------|------|
| `order` | Integer | 步骤序号 |
| `stepName` | String | 步骤名（用作 step scope 命名空间，如 `login`、`createOrder`） |
| `apiSpecId` | String | 关联的 ApiSpec ID |
| `requestTemplate` | JSONB | 请求模板（inputData + headers + queryParams + pathParams） |
| `assertionDefinitions` | AssertionDefinition[] | 断言定义列表 |
| `extractRules` | ExtractionRule[] | 响应提取规则列表 |
| `critical` | Boolean | 是否为关键步骤（失败时是否触发 FAIL_FAST） |

### 4.4 TestCase 内嵌子对象

**ApiTestCaseDetail（mode=SINGLE, detailType=API 时使用）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `method` | String | GET / POST / PUT / DELETE / PATCH |
| `path` | String | 接口路径 |
| `inputData` | JSON | 请求体/请求参数集合 |
| `headers` | JSON | 请求 Header |
| `queryParams` | JSON | Query 参数 |
| `pathParams` | JSON | Path 路径参数 |
| `assertionDefinitions` | AssertionDefinition[] | 断言定义列表 |
| `extractRules` | ExtractionRule[] | 响应提取规则列表 |

**FunctionalTestCaseDetail（mode=SINGLE, detailType=FUNCTIONAL 时使用）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `featureId` | String | 关联功能模块 ID |
| `steps` | FuncStep[] | 操作步骤列表 |
| `testData` | JSON | 测试数据 |
| `expectedChecks` | String[] | 预期检查点列表 |
| `pageOrModule` | String | 所属页面或模块名 |

**AssertionDefinition：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `assertionId` | String | 断言标识 |
| `assertionType` | Enum | `STATUS_CODE` / `JSON_PATH` / `HEADER` / `BODY_TEXT` / `RESPONSE_TIME` |
| `operator` | Enum | `EQ` / `NE` / `IN` / `EXISTS` / `NOT_EXISTS` / `CONTAINS` / `NOT_CONTAINS` / `LT` / `LTE` / `GT` / `GTE` |
| `target` | String | 断言目标（如 `$.code`、`Content-Type`） |
| `expectedValue` | String | 期望值（可含变量表达式 `${...}`） |
| `severity` | Enum | `CRITICAL` / `WARNING` / `INFO` |
| `source` | Enum | `SYSTEM_GENERATED` / `AI_GENERATED` / `MANUAL` / `RULE_GENERATED` |
| `message` | String | 断言失败时的自定义信息 |

**ExtractionRule：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `ruleId` | String | 规则标识 |
| `sourceType` | Enum | `BODY_JSON` / `HEADER` / `STATUS_CODE` |
| `sourcePath` | String | 提取路径（如 `$.data.userId`、`Authorization`） |
| `targetScope` | Enum | `TASK` / `SUITE` / `STEP` |
| `targetKey` | String | 提取后写入的变量名 |
| `required` | Boolean | 提取失败时是否阻断执行 |
| `defaultValue` | String | 提取失败时的默认值（required=false 时生效） |

## 5. 过程层对象

### 5.1 Task

一次生成/确认/执行过程。不拥有 TestCase，而是产出 Draft + 记录执行引用。

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | String (UUID) | 主键 |
| `taskType` | Enum | `API_TEST` / `FUNC_CASE_GEN` / `REGRESSION` |
| `taskName` | String | 任务名称 |
| `status` | Enum | `PENDING` → `ANALYZING` → `CASE_GENERATED` → `EXECUTING` → `ANALYZING_RESULTS` → `COMPLETED` / `FAILED` |
| `sourceType` | Enum | `OPENAPI` / `CODE_REPO` / `REQUIREMENT_DOC` / `MANUAL` |
| `sourceRef` | String | 原始输入来源引用（文档 ID、仓库路径等） |
| `targetApiSpecIds` | String[] | 本次任务涉及的 ApiSpec ID 列表 |
| `priority` | Enum | `LOW` / `MEDIUM` / `HIGH` |
| `creator` | String | 创建人 |
| `metadata` | JSONB | 扩展字段 |
| `createdAt` | DateTime | 创建时间 |
| `updatedAt` | DateTime | 更新时间 |

**状态流转图：**

```
┌──────────┐    ┌────────────┐    ┌───────────────┐    ┌───────────┐    ┌───────────────────┐    ┌───────────┐
│ PENDING  │───▶│ ANALYZING  │───▶│ CASE_GENERATED │───▶│ EXECUTING │───▶│ ANALYZING_RESULTS │───▶│ COMPLETED │
└──────────┘    └────────────┘    └───────────────┘    └───────────┘    └───────────────────┘    └───────────┘
                      │                                                       │                      │
                      └───────────────── FAILED ◀─────────────────────────────┘                      │
                                        (任意阶段可触发失败终止)                                         │
```

### 5.2 PlanStep

| 字段 | 类型 | 说明 |
|------|------|------|
| `stepId` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `stepType` | Enum | `ANALYZE_CODE_API` / `RETRIEVE_KNOWLEDGE` / `GENERATE_CASES` / `EXECUTE_SUITE` / `EXECUTE_BATCH` / `EXECUTE_SINGLE` / `ANALYZE_FAILURE` / `GENERATE_REPORT` / `REFINE_MEMORY` |
| `stepStatus` | Enum | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` / `SKIPPED` |
| `stepOrder` | Integer | 执行顺序 |
| `goal` | String | 本步目标描述 |
| `inputRef` | String | 本步输入引用 |
| `retryCount` | Integer | 已重试次数 |
| `startedAt` | DateTime | 开始时间 |
| `finishedAt` | DateTime | 结束时间 |

### 5.3 TestCaseDraft

任务驱动的用例草稿，绑定 Task。

| 字段 | 类型 | 说明 |
|------|------|------|
| `draftId` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `source` | Enum | `STRUCTURE` / `BUSINESS` / `MEMORY` |
| `stage` | Enum | `STRUCTURE` / `BUSINESS` / `MEMORY`（生成阶段） |
| `status` | Enum | `PENDING_REVIEW` / `PROMOTED` / `DISCARDED` |
| `targetApiSpecId` | String | 目标 ApiSpec ID |
| `dedupKey` | String | 去重 key = `caseType + targetField + normalizedInputPatch + expectedStatusCode` |
| `draftContent` | JSONB | 草稿内容（与 TestCase.detail 结构一致） |
| `promotedCaseId` | String | 提升为正式 TestCase 后的 caseId |
| `createdAt` | DateTime | 创建时间 |

### 5.4 TaskCaseExecution

记录一次 Task 执行了哪些正式 TestCase。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `caseId` | String (UUID) | 外键 → TestCase |
| `executionMode` | Enum | `SINGLE` / `SUITE_STEP` / `BATCH` |
| `snapshotJson` | JSONB | 执行时的 case 快照 |
| `executionStatus` | Enum | `PENDING` / `EXECUTING` / `COMPLETED` / `SKIPPED` |
| `executionRecordId` | String | 外键 → ExecutionRecord |

### 5.5 ExecutionRecord

| 字段 | 类型 | 说明 |
|------|------|------|
| `executionId` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `caseId` | String (UUID) | 外键 → TestCase |
| `stepId` | String (UUID) | 外键 → PlanStep |
| `executorType` | Enum | `HTTP` / `BROWSER` / `MOCK` |
| `environment` | String | `dev` / `test` / `staging` |
| `requestSnapshot` | JSONB | 请求快照 |
| `responseSnapshot` | JSONB | 响应快照 |
| `assertionResults` | JSONB | 断言结果列表 |
| `overallStatus` | Enum | `PASSED` / `PASSED_WITH_WARNINGS` / `FAILED` |
| `criticalFailed` | Boolean | 是否存在 CRITICAL 断言失败 |
| `durationMs` | Long | 执行耗时（毫秒） |
| `statusCode` | Integer | HTTP 状态码 |
| `errorMessage` | String | 异常信息 |
| `createdAt` | DateTime | 执行时间 |

### 5.6 Observation

| 字段 | 类型 | 说明 |
|------|------|------|
| `observationId` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `executionId` | String (UUID) | 外键 → ExecutionRecord |
| `observationType` | Enum | `ASSERTION_FAILURE_ANALYSIS` / `RISK_EVALUATION` / `RETRY_SUGGESTION` / `MEMORY_CANDIDATE` / `GENERAL_COMMENT` |
| `summary` | String | 观察结论摘要 |
| `failureReason` | String | 失败归因 |
| `riskLevel` | Enum | `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `nextSuggestion` | String | 下一步建议 |
| `source` | Enum | `SYSTEM` / `AI` / `MANUAL` |
| `createdAt` | DateTime | 生成时间 |

```
ExecutionRecord 1 ——— N Observation
```

### 5.7 ChangeLog

V1 轻量变更备份，每次覆盖前备份旧内容（前镜像）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `changeId` | String (UUID) | 主键 |
| `entityType` | Enum | `TEST_CASE` / `API_SPEC` |
| `entityId` | String | 被修改实体 ID |
| `beforeSnapshot` | JSONB | 修改前完整快照 |
| `afterSnapshot` | JSONB | 修改后完整快照 |
| `changeType` | Enum | `AUTO_GENERATED` / `MANUAL_EDIT` / `PROMOTION` |
| `changedBy` | String | 操作人（或 `SYSTEM` / `AI`） |
| `taskId` | String | 关联 Task（自动生成时有值） |
| `createdAt` | DateTime | 变更时间 |

### 5.8 Report

| 字段 | 类型 | 说明 |
|------|------|------|
| `reportId` | String (UUID) | 主键 |
| `taskId` | String (UUID) | 外键 → Task |
| `summary` | String | 整体结论 |
| `caseCount` | Integer | 总用例数 |
| `passCount` | Integer | 通过数 |
| `failCount` | Integer | 失败数 |
| `warningCount` | Integer | 警告数 |
| `riskSummary` | String | 风险摘要 |
| `findings` | JSONB | 缺陷/异常/风险项列表 |
| `suggestions` | JSONB | 修复建议、补测建议、回归建议 |
| `createdAt` | DateTime | 生成时间 |

## 6. MemoryItem（摘要）

完整定义见 02-PRD。此处仅列概要：

| 字段 | 类型 | 说明 |
|------|------|------|
| `memoryId` | String (UUID) | 主键 |
| `memoryType` | Enum | `SESSION` / `TASK` / `LONG_TERM` |
| `scopeType` | Enum | `project_knowledge` / `testing_pattern` / `failure_pattern` / `preference` |
| `summary` | String | 记忆摘要 |
| `tags` | String[] | 检索标签 |
| `importance` | Float (0-1) | 重要度评分 |

物理存储分层：Session → Redis TTL；Task → PG 单独表；Long-term → PG + pgvector（bge-m3, 1024 dim）。

## 7. 对象关系总览

```
资产层：
  ApiSpec ───────────── 接口定义资产
    └─ TestCase (1:N) ── 正式测试用例
         ├─ mode=SINGLE → primaryApiSpecId + detail (ApiTestCaseDetail)
         └─ mode=SUITE → steps[] (TestCaseStep[], 每个 step 含 apiSpecId)

过程层：
  Task ──────────────── 一次生成/确认/执行过程
    ├─ PlanStep (1:N) ──── 编排执行步骤
    ├─ TestCaseDraft (1:N) ── 生成的用例草稿（→ 提升 → TestCase）
    ├─ TaskCaseExecution (1:N) ── 本次执行的正式 case 引用
    │    └─ ExecutionRecord (1:1) ── 原始执行事实
    │         └─ Observation (1:N) ── 分析层结果
    ├─ ChangeLog (1:N) ── 轻量变更备份
    └─ Report (1:1) ── 最终报告

跨层：
  MemoryItem ─────────── 统一记忆抽象
```

## 8. 两条数据主线

### 8.1 任务主线

```
Task → PlanStep → TaskCaseExecution → ExecutionRecord → Observation → Report
                         ↑
                   TestCase（资产层，被引用而非拥有）
```

### 8.2 用例生命周期主线

```
Task → TestCaseDraft（生成，绑定 taskId）
  → 用户确认 → TestCase（提升为资产，归属 ApiSpec，脱离 Task）
  → Task（新任务） → TaskCaseExecution（引用已有 TestCase）
```

## 9. V1 数据变更流程

### 9.1 自动生成覆盖流程

```
1. 新 Task 生成 TestCaseDraft
2. 按 dedupKey 查找已有 TestCase（同一 primaryApiSpecId 下）
3. 找到匹配：
   a. locked=true → 跳过，保留原样
   b. manualEdited=true → 生成候选更新附加，不直接覆盖
   c. 都 false → 覆盖前写 ChangeLog（beforeSnapshot），再更新
4. 未找到匹配：
   → 新建 TestCase
```

### 9.2 人工编辑流程

```
1. 用户编辑 TestCase
2. 系统写 ChangeLog（beforeSnapshot + afterSnapshot + changeType=MANUAL_EDIT）
3. 更新 TestCase，manualEdited=true
```

### 9.3 回归任务流程

```
1. 用户按 primaryApiSpecId + tags + scenarioName 筛选已有 TestCase
2. 选定后创建新 Task，引用这些 TestCase
3. TaskCaseExecution 执行
```

## 10. Java 包结构建议

```
com.xxx.agent.model
com.xxx.agent.model.apispec           // ApiSpec
com.xxx.agent.model.testcase           // TestCase, TestCaseStep
com.xxx.agent.model.testcase.api      // ApiTestCaseDetail (内嵌)
com.xxx.agent.model.testcase.func     // FunctionalTestCaseDetail (内嵌)
com.xxx.agent.model.task              // Task, PlanStep
com.xxx.agent.model.draft             // TestCaseDraft
com.xxx.agent.model.execution         // TaskCaseExecution, ExecutionRecord
com.xxx.agent.model.observation       // Observation
com.xxx.agent.model.changelog         // ChangeLog
com.xxx.agent.model.memory            // MemoryItem
com.xxx.agent.model.memory.session
com.xxx.agent.model.memory.task
com.xxx.agent.model.memory.longterm
com.xxx.agent.model.report            // Report
```

## 11. 第一版范围

**包含：**
- 资产层 2 个对象：ApiSpec、TestCase（含 TestCaseStep）
- 过程层 9 个对象：Task、PlanStep、TestCaseDraft、TaskCaseExecution、ExecutionRecord、Observation、ChangeLog、MemoryItem、Report
- Task 状态机（6 状态流转）
- TestCase 直接归属 ApiSpec（primaryApiSpecId 非空）
- SUITE 模式通过 TestCase.steps[] 承载跨接口链路
- Draft → TestCase 提升流程（dedupKey 去重）
- manualEdited / locked 保护机制
- ChangeLog 前镜像备份
- 回归按 `primaryApiSpecId` + `tags` + `scenarioName` 筛选

**暂不包含：**
- TestPlan（V2 视需要引入）
- TestCase 正式版本化（TestCaseVersion 表）
- UI 自动化复杂对象模型
- 多租户权限模型
- DAG 子任务实例模型
