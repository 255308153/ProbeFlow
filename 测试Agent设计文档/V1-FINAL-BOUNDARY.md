# V1 最终边界确认（21 项决策）

## 一、产品边界

### 1. 测试范围
V1 仅 HTTP API 测试。`caseCategory=FUNCTIONAL` 为 V2 预留，V1 无生成和执行链路。SINGLE/SUITE/BATCH 均为 HTTP 执行组织模式。不做 UI 自动化、Service 直调、DB 直连断言。

### 2. 运行模式
全自动（CI/回归，自动提升 Draft）和半自动（首次探索，CASE_GENERATED 后暂停等人确认）。Task 状态机：`PENDING → ANALYZING → CASE_GENERATED → [WAITING_FOR_REVIEW?] → EXECUTING → ANALYZING_RESULTS → COMPLETED / FAILED / CANCELLED`。

### 3. 知识库 RAG ≠ 记忆系统
- **RAG**：管文档知识（人类写好的文档），完整检索系统
- **Memory Refinery**：管运行经验（Agent 跑出来的 pattern），异步后台提纯，所有记忆写入的唯一入口
- **Unified Context Builder**：读路径把两边拼到一起

## 二、生成与资产

### 4. SUITE 生成走独立路径
先为每个 ApiSpec 生成 SINGLE case → 识别链路 → 复用 SINGLE 的 requestTemplate 组装 steps[] → 链路增强。V1 SUITE 来源仅限用户显式选择和知识库 business_flow 文档。

### 5. ExtractionRules 由 DependencyLinker 生成
SUITE 生成管线中的 DependencyLinker 同时产出 extractRules 和 `${suite.xxx}` 变量引用。代码分析只提供结构线索。规则系统不管 extractRules。执行引擎只消费。

### 6. SUITE steps[] 是快照，不是引用
冻结是 feature。不自动同步底层 SINGLE。配套 stale 检测 + 显式刷新。检测强、自动修复弱。

### 7. ApiSpec 版本变化 → TestCase 过时检测（V1 必做）
TestCase 增加 `basedOnApiSpecVersion`、`basedOnApiSpecFingerprint`、`staleStatus`。回归任务创建时提示 stale case。不自动覆盖，走 Draft → 确认 → Promotion。

### 8. Business/Memory 生成重叠
接受少量重叠。MemoryCaseEnhancer prompt 传入已生成 Draft 摘要做软约束。CaseNormalizer 确定性去重兜底。不做 LLM 语义去重。

## 三、编排与执行

### 9. 编排模式：模板驱动 + 按需 Planner
正常路径走 TaskTemplateRegistry 预定义模板。Planner 只在分叉决策、异常决策、半自动恢复后重规划三类场景被唤醒。不是纯 Agent Loop，不是 DAG 引擎。

### 10. MemoryRefinery 不是编排步骤
删除 `REFINE_MEMORY` StepType。提纯是事件驱动的异步后台任务（回后/阶段结束/任务结束三个触发时机，候选只被提纯一次）。主循环只做 `writeTaskMemory` + `enqueueMemoryCandidate`。

### 11. 代码分析 readiness gate
ApiSpec 增加 readiness 标志位（routeReady、dtoExpanded、validationReady、authReady 等）。每个 PlanStepType 声明最低依赖。PARTIAL 状态下不生成正式 TestCaseDraft。

### 12. 任务创建是编排系统的前置步骤
TaskInitializationService 负责创建 Task + 补齐 targetApiSpecIds + 初始化执行项。回归任务跳过接口分析和用例生成，模板：`PrepareExecutionInput → ExecuteBatch → AnalyzeResults → GenerateReport`。

### 13. SourceMaterial 输入物料统一入口
轻量模型，Task 保留 sourceType 决定模板走向，SourceMaterial[] 描述实际物料。SourceIngestionService 负责 clone/解压/导入。分析工具只消费已就绪物料。V1 主入口：CODE_REPO / OPENAPI / MANUAL；REQUIREMENT_DOC 仅作辅助输入。

### 14. 执行前 readiness gate
EXECUTE_* 检查 `baseUrlReady + environmentReady + authCredentialReady`。不满足 → `COMPLETED_WITH_BLOCKERS`（已完成分析和生成，执行被跳过）。不硬跑产噪音。

### 15. SUITE Token 过期
不做自动刷新。401 + 使用前序步骤提取的认证变量 → 基础归因"疑似 Token 过期" → 终止 SUITE。建议拆链路或提高 Token TTL。

## 四、断言与归因

### 16. expectedStatusCode 唯一权威来源是 TestCase 实例
删除 07 的 caseType → statusCode 映射。05/06 生成时写实 expectedStatusCode。07 改为读取 expectedStatusCode 生成 STATUS_CODE 断言。

### 17. StepOutcome ≠ Observation
StepOutcome（编排运行时内存态）和 Observation（分析持久态）是两个不同对象。PlanStep 执行 → StepOutcome → 若需分析 → Observation（1 条 ExecutionRecord 可挂 N 条）。

### 18. 失败归因两层
- 基础归因（自动必做）：每次 EXECUTE 失败后基于规则推断，不调 LLM。产出 `analysisLevel=BASIC` 的 Observation
- 深度分析（Planner 按需触发）：ANALYZE_FAILURE 收缩为深度分析步骤

## 五、数据一致性

### 19. 知识库文档 Revision 机制
`documentId + docRevisionId` 双层 ID。更新 → 新 revision → 旧 chunk SUPERSEDED → 默认检索过滤。旧 chunk 不物理删除（历史引用溯）。文档自动切换，不需人工审批。

### 20. 并发安全
ExecutionContext、Task Memory 由 taskId 天然隔离。RAG chunk weight 更新用 SQL 原子增量（`authority = authority + delta`）。V1 不引入分布式锁。

### 21. 报告系统
结构化聚合器。数据来源：Task + ExecutionRecord[] + Observation[]。summary 骨架代码生成 + 可选 AI 润色。findings/suggestions 结构化回链证据。失败任务出 PARTIAL 报告。仅 JSON 输出。
