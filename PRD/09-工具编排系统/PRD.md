# 09-工具编排系统 PRD

## 1. 模块定位

工具编排系统是测试 Agent 的"大脑"，负责把所有底层模块（接口分析、规则系统、用例生成、执行引擎、断言、记忆）串联成一个可循环决策、可调用工具、可回收观察结果的智能工作流。

它不是静态 DAG 引擎，而是一个**有状态的 Agent Workflow 主循环**。

```
Task Input
  → OrchestrationEngine（主循环）
    → ContextBuilder（构建上下文）
    → Planner（AI 决策下一步）
    → ToolRouter（静态映射工具）
    → ExecutionController（发起执行）
    → ObservationCollector（收集观察结果）
    → TaskStateStore（更新状态 + 写记忆）
    → LoopDecider（判断继续/结束）
  → Report
```

**核心公式：`Agent Workflow 主循环 + 标准化工具路由 + 标准化观察结果 + 可扩展的 DAG 子流程`**

## 2. 核心设计原则

### 2.1 主编排采用 Agent Loop

主编排采用循环式决策模型，不一次性静态编排全流程：

```
构建上下文 → 决策下一步 → 路由工具 → 执行工具 → 收集观察 → 更新状态 → 判断是否继续
```

### 2.2 子流程可逐步 DAG 化

对于稳定、可批量、可并行的执行链路，后续可下沉为 DAG/Job Flow。V1 先以 Agent Loop 为主，BATCH/SUITE 作为固定子流程处理。

### 2.3 编排与工具解耦

编排层通过统一 `Tool` 接口调用工具，模块内部是 Java Service，外面包一层 Tool Adapter。后续替换执行器、增加新工具时，主编排层不需要大改。

### 2.4 编排与记忆分层

- **编排系统**：负责"下一步做什么"（决策和工具执行闭环）
- **记忆系统**：负责"拿什么上下文来做"（沉淀经验与统一上下文）

两者协作但职责分离。

### 2.5 AI 做高层决策，底层执行走固定路径

```
AI 决定：做什么（stepType）、什么顺序、失败后怎么处理
系统决定：具体用哪个工具（静态映射）、怎么执行（本地代码）
```

## 3. 总架构

```
┌──────────────────────────────────────────────────────────────┐
│                    OrchestrationEngine                        │
│                                                              │
│  while (task not finished) {                                 │
│    context = ContextBuilder.build(taskState, memory,         │
│                                   externalMaterials)         │
│    steps = Planner.planNext(context, taskState)  // AI       │
│    for each step:                                            │
│      tool = ToolRouter.route(step)               // 静态映射  │
│      result = ExecutionController.execute(tool, step)        │
│      observation = ObservationCollector.collect(result)      │
│      taskState = TaskStateStore.update(taskState,            │
│                                         step, observation)   │
│      memory.write(taskState, observation)                    │
│      if LoopDecider.shouldBreak(taskState, observation)      │
│        break  // 触发重规划                                    │
│    decision = LoopDecider.decide(taskState)                  │
│  }                                                           │
│  return Report                                               │
└──────────────────────────────────────────────────────────────┘
```

## 4. 模块拆分

| 组件 | 职责 | AI/本地 |
|------|------|:------:|
| `OrchestrationEngine` | 主入口，驱动主循环执行，协调各模块调用顺序 | 本地 |
| `ContextBuilder` | 为当前轮次构建可执行上下文（含记忆召回） | 本地 |
| `Planner` | 决定当前轮次的下一步动作（一次可规划 1~5 步） | **AI** |
| `ToolRouter` | 根据 PlanStepType 静态映射到具体工具 | 本地 |
| `ExecutionController` | 真正发起工具执行（超时/重试/并发/中断） | 本地 |
| `ObservationCollector` | 把工具原始输出转成标准化 Observation | 本地 |
| `TaskStateStore` | 保存任务全局状态，支持断点续跑 | 本地 |
| `LoopDecider` | 判断任务继续/结束/等待外部输入 | 本地 |

## 5. Planner 设计

### 5.1 决策模式

**不要求一次性产出全量计划，每轮可产出 1~5 个 PlanStep。**

执行模式：
```
plan (N steps) → execute step1 → observe → keep/revise plan → execute step2 → ...
```

每步执行后允许：
- **继续原计划**：当前步成功，计划无需调整
- **插入新步骤**：观察到新情况（如失败需要分析），在剩余计划前插入新步骤
- **截断重规划**：重大变化（如环境异常、核心 case 全失败），丢弃剩余计划，重新让 AI 决策

### 5.2 Planner 输入

```
- TaskState（当前任务状态：阶段、已完成步骤、最近观察）
- ContextBundle（记忆系统召回 + 外部材料）
- 当前 Plan（如果还有未执行步骤）
- 上一轮 Observation（如果刚执行完一步）
```

### 5.3 Planner 输出

```java
public class PlanResult {
    List<PlanStep> steps;        // 1~5 个步骤
    String reasoning;            // AI 决策理由（可解释性）
    boolean replacesPrevious;    // true=截断重规划, false=追加到现有计划
}
```

### 5.4 推荐的 StepType 枚举

| StepType | 说明 | 路由目标 |
|----------|------|---------|
| `ANALYZE_CODE_API` | 分析代码中的接口定义 | CodeImportAnalyzerService |
| `RETRIEVE_KNOWLEDGE` | 从知识库召回业务上下文 | KnowledgeRetriever |
| `GENERATE_CASES` | 生成测试用例 | CaseGenerationEngine |
| `EXECUTE_SUITE` | 执行一组接口测试用例 | ExecutionEngine (SUITE) |
| `EXECUTE_BATCH` | 并行执行独立接口用例 | ExecutionEngine (BATCH) |
| `EXECUTE_SINGLE` | 执行单条用例 | ExecutionEngine (SINGLE) |
| `ANALYZE_FAILURE` | 分析失败原因 | FailureAnalyzer |
| `GENERATE_REPORT` | 生成最终测试报告 | ReportGenerator |
| `REFINE_MEMORY` | 触发记忆提纯 | MemoryRefineryService |

### 5.5 Planner 约束

为控制质量和行为，Planner 受以下约束：

| 约束 | 说明 |
|------|------|
| 单轮最多 5 步 | 防止一次性规划过长导致失控 |
| 不允许跳过关键步骤 | 如 `ANALYZE_CODE_API` 必须在 `GENERATE_CASES` 之前 |
| 失败后必须分析 | 执行失败时，下一步必须是 `ANALYZE_FAILURE` 或 `REFINE_MEMORY` |
| 未知状态降级 | Planner 不确定时，默认只产 1 步 |

## 6. ToolRouter 设计

### 6.1 路由方式

**AI 决定 stepType → ToolRouter 静态映射工具。**

不让 AI 在运行时自由选择底层工具。如果一个 stepType 对应多个实现，再由 ToolRouter 做策略选择。

### 6.2 静态映射表

```java
public class ToolRouter {
    private final Map<PlanStepType, Tool> routingTable = Map.of(
        ANALYZE_CODE_API,    analyzeApiTool,
        RETRIEVE_KNOWLEDGE,  knowledgeRetrieverTool,
        GENERATE_CASES,      caseGenerationTool,
        EXECUTE_SUITE,       executionEngineTool,
        EXECUTE_BATCH,       executionEngineTool,
        EXECUTE_SINGLE,      executionEngineTool,
        ANALYZE_FAILURE,     failureAnalyzerTool,
        GENERATE_REPORT,     reportGeneratorTool,
        REFINE_MEMORY,       memoryRefineryTool
    );

    public Tool route(PlanStep step) {
        Tool tool = routingTable.get(step.stepType);
        if (tool == null) throw new NoToolFoundException(step.stepType);
        return tool;
    }
}
```

### 6.3 Tool Adapter 模式

每个底层模块通过 Tool Adapter 暴露给编排系统：

```
底层 Java Service (InterfaceAnalysisService)
  → Tool Adapter (AnalyzeApiTool implements Tool)
    → 编排层统一调用 tool.execute(input)
```

```java
public interface Tool {
    String getToolName();
    ToolInputSchema getInputSchema();
    ExecutionResult execute(ToolInput input, ExecutionContext ctx);
}
```

**好处：**
- 编排系统只依赖统一 Tool 协议
- 模块实现可替换，不影响编排层
- 后续接 MCP 工具、外部工具时扩展自然

## 7. ExecutionController 设计

### 7.1 职责

- 工具入参准备与校验
- 单工具执行
- 多工具串行执行（SUITE 模式，按计划依次执行）
- 超时控制（每步可配置超时）
- 重试控制（PlanStep 级别的重试，不同于 HTTP 层重试）
- 失败中断（关键步骤失败时停止后续执行）

### 7.2 执行模式

| 模式 | 说明 |
|------|------|
| `SERIAL` | 按 PlanStep 顺序串行执行（默认） |
| `BACKGROUND` | 后台执行，不阻塞主循环（如 REPORT 生成） |

V1 编排层只有 SERIAL 模式。BATCH 并行在执行引擎内部完成（08-执行引擎），编排层视为一个工具调用。

### 7.3 超时配置

| 层级 | 说明 |
|------|------|
| PlanStep 级别 | 每类 stepType 有默认超时（如 EXECUTE_SUITE=600s, GENERATE_CASES=120s） |
| 可覆盖 | PlanStep 中可单独指定 timeoutMs |

## 8. ObservationCollector 设计

### 8.1 职责

把工具原始输出转成标准化 Observation，不直接把原始 response 交给主循环。

### 8.2 各类工具的观察摘要

| 工具 | Observation 内容 |
|------|-----------------|
| AnalyzeApiTool | 识别到的接口数量、Controller 数、DTO 字段数、鉴权类型 |
| KnowledgeRetrieverTool | 召回 chunk 数、匹配的文档类型、Top 3 文档标题 |
| CaseGenerationTool | 生成 case 数、各来源分布（STRUCTURE/BUSINESS/MEMORY）、生成耗时 |
| ExecutionEngine (SUITE/BATCH/SINGLE) | 执行 case 数、通过/失败/警告数、失败明细摘要、总耗时 |
| FailureAnalyzer | 分析结论、根因判断、建议下一步动作 |
| ReportGenerator | 报告 ID、摘要 |

### 8.3 Observation 对象

```java
public class Observation {
    String observationId;
    String taskId;
    String stepId;
    ObservationType type;        // EXECUTION_RESULT / FAILURE_ANALYSIS / ...
    String summary;              // 一句话摘要
    AssertionSummary assertionResult;  // 断言汇总（仅执行类步骤）
    RiskLevel riskLevel;         // LOW / MEDIUM / HIGH / CRITICAL
    String failureReason;        // 失败原因（仅失败步骤）
    String nextSuggestion;       // 下一步建议（供 Planner 参考）
    List<MemoryCandidate> memoryCandidates;  // 可提纯记忆候选
    Map<String, Object> metadata;
}
```

## 9. TaskStateStore 设计

### 9.1 职责

编排系统的状态底座。保存任务全局状态、当前阶段、已完成步骤、失败信息，支持断点续跑。

### 9.2 TaskState

```java
public class TaskState {
    String taskId;
    TaskStatus status;           // PENDING → ANALYZING → ... → COMPLETED / FAILED
    String currentStage;         // 当前阶段描述
    List<String> targetApiSpecIds;  // 本次任务涉及的 ApiSpec ID 列表
    List<PlanStep> completedSteps;
    List<PlanStep> pendingSteps; // 当前计划中未执行的步骤
    List<String> pendingDraftIds;// 本次生成的 TestCaseDraft ID 列表
    Integer retryCount;
    Observation lastObservation;
    String errorCode;
    String errorMessage;
    String nextActionHint;       // 恢复时给 Planner 的提示
    ExecutionContextSnapshot contextSnapshot;  // 关键变量快照
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### 9.3 快照时机

| 时机 | 内容 |
|------|------|
| 每个 PlanStep 执行前 | TaskState 全量快照 |
| 每个 PlanStep 完成后 | 更新 completedSteps + lastObservation + contextSnapshot |
| SUITE 执行中（每步） | 更新 contextSnapshot（变量累积） |
| 任务状态变更时 | 更新 status + currentStage |

## 10. 断点续跑

### 10.1 恢复流程

```
1. 加载 TaskState（从 PG）
2. 检查 pendingSteps 是否非空
   - 是 → 继续执行 pendingSteps
   - 否 → 进入恢复重建
3. 恢复重建：
   a. 从 contextSnapshot 恢复 ExecutionContext
   b. 从 Task Memory 加载任务历史
   c. 构建 ContextBundle
4. Planner 基于恢复后的状态决策下一步
   - 输入：当前 TaskState + ContextBundle + nextActionHint
5. 继续主循环
```

### 10.2 恢复策略

```
核心原则：快照恢复状态，Planner 再接着决策。

不是让 Planner "猜"当前任务到哪了，
而是先用快照精确恢复到最近稳定状态，
再让 Planner 决策从哪继续。
```

### 10.3 可恢复 vs 不可恢复

| 可恢复场景 | 不可恢复场景 |
|-----------|------------|
| 系统重启 | 输入材料（代码仓库/文档）已不可访问 |
| 工具执行超时 | 核心配置已被删除或损坏 |
| 单步失败（非致命） | 任务已被手动终止 |
| 外部服务临时不可用 | 超出最大重试次数 |

## 11. LoopDecider 设计

### 11.1 判断维度

| 维度 | 说明 |
|------|------|
| 目标是否达成 | 所有关键步骤是否已完成（通过 completedSteps 判断） |
| 计划是否已空 | pendingSteps 是否为空，且 Planner 未产出新步骤 |
| 是否需要重规划 | 当前步失败且需要分析，触发 break 回到 Planner |
| 是否达到上限 | maxSteps（默认 50）、maxRetries（默认 3） |
| 是否出现不可恢复错误 | 核心输入缺失、环境配置错误等 |

### 11.2 决策输出

```java
public enum LoopDecision {
    CONTINUE,                  // 继续执行下一个 PlanStep
    REPLAN,                    // 中断当前计划，让 Planner 重规划
    FINISH_SUCCESS,            // 任务成功结束
    FINISH_FAIL,               // 任务失败结束
    WAITING_EXTERNAL_INPUT     // 暂停，等待用户输入（V1 可选）
}
```

### 11.3 默认结束条件

```
FINISH_SUCCESS:
  - 所有 case 执行完毕
  - 报告生成完毕
  - 无未处理失败

FINISH_FAIL:
  - 输入材料严重缺失
  - 超出 maxRetries
  - 核心步骤反复失败
  - 用户手动终止

REPLAN:
  - 当前步失败且非关键（需要 Planner 判断是否继续/跳过/补测）
  - 执行结果和预期差异大（需要 Planner 重新评估策略）
```

## 12. 主循环完整模板

```
OrchestrationEngine.run(taskId):

1. taskState = TaskStateStore.load(taskId)
   if (taskState == null):
     taskState = TaskStateStore.init(taskId, taskInput)
     // taskInput 含 targetApiSpecIds（用户选定的接口列表或代码目录）

2. while (LoopDecider.shouldContinue(taskState) == CONTINUE):

   // --- 阶段 A: 构建上下文 ---
   context = ContextBuilder.build(taskState)
   // 含：记忆系统召回 + Task State + 外部材料
   // 如果指定了 targetApiSpecIds → 加载已有 TestCase（用于 dedupKey 去重检测）

   // --- 阶段 B: AI 决策 ---
   planResult = Planner.planNext(context, taskState)
   // AI 产出 1~5 个 PlanStep

   // --- 阶段 C: 执行 + 观察 ---
   for each step in planResult.steps:
     tool = ToolRouter.route(step)
     result = ExecutionController.execute(tool, step, context)

     observation = ObservationCollector.collect(result)

     // 如果是 GENERATE_CASES 步骤：写入 TestCaseDraft
     if (step.stepType == GENERATE_CASES):
       taskState.pendingDrafts = result.draftIds
       TaskStateStore.saveDrafts(taskId, result.drafts)

     // 如果是 EXECUTE_SUITE/EXECUTE_BATCH：写入 TaskCaseExecution + ExecutionRecord
     if (step.stepType in [EXECUTE_SUITE, EXECUTE_BATCH, EXECUTE_SINGLE]):
       TaskCaseExecutionStore.saveAll(taskId, result.executedCaseIds)
       ExecutionRecorder.saveAll(result.executionRecords)

     taskState = TaskStateStore.update(taskState, step, observation)
     MemoryService.writeTaskMemory(taskState, observation)

     // 判断是否需要中断当前计划
     if (LoopDecider.shouldBreak(taskState, observation)):
       taskState.pendingSteps = remainingSteps  // 保存剩余步骤
       break  // 回到外层 AI 决策

   // --- 阶段 D: 判断是否继续 ---
   decision = LoopDecider.decide(taskState)
   if (decision == FINISH_SUCCESS || decision == FINISH_FAIL):
     break
   if (decision == REPLAN):
     taskState.pendingSteps = []  // 清空，让 AI 重新规划
   if (decision == WAITING_EXTERNAL_INPUT):
     TaskStateStore.save(taskState)  // 保存状态，暂停
     return

3. // --- 阶段 E: 收尾 ---
   if (taskState.status == COMPLETED):
     MemoryRefineryService.refineTask(taskId)  // 任务结束后触发提纯
   return ReportGenerator.generate(taskId)

4. // --- 阶段 F: Draft 提升（用户确认后）---
   // 这一阶段不在此循环中，由前端触发
   // DraftPromotionService.promote(draftIds):
   //   对每条 draft，按 dedupKey 查找已有 TestCase → 更新或新建 → 写 ChangeLog
```

## 13. 异常与恢复机制

### 13.1 异常分类

| 异常类型 | 示例 | 处理方式 |
|---------|------|---------|
| 可恢复-重试 | 工具超时、外部接口临时失败 | 重试该 PlanStep（最多 N 次） |
| 可恢复-降级 | 输出格式不满足要求 | 降级工具或降低执行粒度 |
| 可恢复-重规划 | 执行结果不符合预期 | 抛回 Planner 重规划 |
| 不可恢复 | 核心输入缺失、配置错误 | 标记任务失败、生成失败报告 |

### 13.2 RetryPolicy（PlanStep 级别，非 HTTP 级别）

| 策略 | 行为 |
|------|------|
| `NONE` | 不重试 |
| `SIMPLE_RETRY` | 固定间隔重试（1s） |
| `EXPONENTIAL_BACKOFF` | 指数退避（500ms → 1s → 2s） |
| `REPLAN_AFTER_FAIL` | 重试失败后抛回 Planner（非简单重试） |

## 14. DAG 子流程扩展规划

### 14.1 V1 状态

V1 主干是 Agent Loop。以下场景作为固定子流程直接在 ExecutionEngine 内部处理，不在编排层以 DAG 形式暴露：

- 批量接口执行（BATCH） → ExecutionEngine 内部并行
- 套件顺序执行（SUITE） → ExecutionEngine 内部串行
- 登录→下单→查询 等稳定链路 → 以 SUITE 模式提交

### 14.2 V2 扩展方向

- DAG 节点定义（每个节点 = 一个 PlanStep + Tool）
- DAG 编排引擎（并行/串行/条件分支/扇出扇入）
- 可视化 DAG 编排界面
- 定时/Cron 触发 DAG
- 多环境并行回归 DAG

## 15. 核心对象模型

### 15.1 PlanStep

```java
public class PlanStep {
    String stepId;
    String taskId;
    PlanStepType stepType;       // 枚举
    StepStatus status;           // PENDING / RUNNING / SUCCESS / FAILED / SKIPPED
    Integer stepOrder;
    String goal;                 // 本步目标，供 Planner 消费
    String inputRef;             // 输入引用（caseId、apiId 等）
    PlanStepRetryPolicy retryPolicy;
    Integer retryCount;
    Duration timeoutMs;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
}
```

### 15.2 ToolDescriptor

```java
public class ToolDescriptor {
    String toolName;
    PlanStepType supportedStepType;
    ToolInputSchema inputSchema;
    Duration defaultTimeoutMs;
    PlanStepRetryPolicy defaultRetryPolicy;
    boolean concurrencySafe;     // 是否支持并行调用
}
```

## 16. V1 StepType → Tool 完整映射

| StepType | Tool | 对应模块 |
|----------|------|---------|
| `ANALYZE_CODE_API` | AnalyzeApiTool | 04-接口自动分析 |
| `RETRIEVE_KNOWLEDGE` | KnowledgeRetrieverTool | 04-知识库 RAG |
| `GENERATE_CASES` | CaseGenerationTool | 06-用例生成 |
| `EXECUTE_SUITE` | ExecutionEngineTool | 08-执行引擎 (SUITE) |
| `EXECUTE_BATCH` | ExecutionEngineTool | 08-执行引擎 (BATCH) |
| `EXECUTE_SINGLE` | ExecutionEngineTool | 08-执行引擎 (SINGLE) |
| `ANALYZE_FAILURE` | FailureAnalyzerTool | AI（06-用例生成 + 04-知识库 + 02-记忆融合分析） |
| `GENERATE_REPORT` | ReportGeneratorTool | 报告系统 |
| `REFINE_MEMORY` | MemoryRefineryTool | 02-记忆系统 |

## 17. 与各模块的关系

| 模块 | 调用方式 |
|------|---------|
| 01-数据模型 | Task 关联 targetApiSpecIds。GENERATE_CASES → TestCaseDraft。EXECUTE_* → TaskCaseExecution + ExecutionRecord |
| 02-记忆系统 | ContextBuilder 调用 UnifiedContextBuilder；每步执行后写 Task Memory |
| 04-接口分析 | 通过 AnalyzeApiTool 调用；通过 KnowledgeRetrieverTool 调用知识库 |
| 06-用例生成 | 通过 CaseGenerationTool 调用 CaseGenerationEngine（输入 ApiSpec + ExistingCases 做 dedupKey 检测，输出 TestCaseDraft[]） |
| 08-执行引擎 | 通过 ExecutionEngineTool 调用（SINGLE/SUITE/BATCH）。SUITE 时执行引擎直接从 TestCase.steps[] 读取步骤定义 |
| 07-断言系统 | 在执行引擎内部消费，编排层不直接调用 |
| 报告系统 | 通过 ReportGeneratorTool 调用 |

### 17.1 Task 与 TestCase 的生命周期

```
1. Task 创建
   Task.targetApiSpecIds = [api-1, api-2, ...]
   → ANALYZE_CODE_API 产出/更新 ApiSpec

2. GENERATE_CASES
   → CaseGenerationTool 产出 TestCaseDraft[]（每个 draft 绑定 taskId + targetApiSpecId）
   → 按 dedupKey 检测已有 TestCase（含 manualEdited/locked 保护）
   → TaskState.pendingDraftIds = [...]
   → 状态 → CASE_GENERATED

3. 用户确认 Draft
   → DraftPromotionService.promote(draftIds):
     对每条 draft，在同一 primaryApiSpecId 下查重 → 更新或新建 TestCase → 写 ChangeLog
   → draft.status = PROMOTED / DISCARDED

4. EXECUTE_SUITE / EXECUTE_BATCH / EXECUTE_SINGLE
   → 执行引擎调用
   → TaskCaseExecutionStore.saveAll(taskId, caseIds, snapshotJson)
   → ExecutionRecord 落库

5. Task 结束
   → TestCase 脱离 Task，作为持久资产留在 ApiSpec 下
   → 下次回归任务：按 primaryApiSpecId + tags + scenarioName 筛选已有 TestCase → 新 Task 引用
```

## 18. Java 包结构建议

```
com.xxx.agent.orchestration
com.xxx.agent.orchestration.engine        // OrchestrationEngine（主入口 + 主循环）
com.xxx.agent.orchestration.state         // TaskStateStore, TaskState
com.xxx.agent.orchestration.context       // ContextBuilder
com.xxx.agent.orchestration.plan          // Planner
com.xxx.agent.orchestration.route         // ToolRouter, Tool 接口
com.xxx.agent.orchestration.execute       // ExecutionController
com.xxx.agent.orchestration.observe       // ObservationCollector, Observation
com.xxx.agent.orchestration.loop          // LoopDecider
com.xxx.agent.orchestration.tool          // Tool Adapter 实现（AnalyzeApiTool, CaseGenerationTool, ...）
com.xxx.agent.orchestration.model         // PlanStep, ToolDescriptor, PlanResult, LoopDecision
```

## 19. 第一版落地范围

### 包含

- 8 个核心组件：Engine / ContextBuilder / Planner / ToolRouter / ExecutionController / ObservationCollector / TaskStateStore / LoopDecider
- 9 种 StepType → Tool 映射
- Agent Loop 主循环（plan → execute → observe → replan）
- Planner 一次产 1~5 步 + 执行后可重规划
- Tool Adapter 模式（模块 Java Service + Adapter 包给编排层）
- 断点续跑（快照恢复 + Planner 续决策）
- 异常分类与 PlanStep 级重试
- SUITE/BATCH 作为固定子流程在 ExecutionEngine 内部处理

### 暂不包含

- 完整 DAG 编排平台
- 可视化 DAG 编辑器
- 定时/Cron 调度
- 多环境并行回归自动编排
- 用户交互式断点调试

## 20. 一句话总结

工具编排系统是整个测试 Agent 的"大脑"，**以 Agent Loop 作为主编排骨架，AI 做高层决策（做什么、什么顺序、失败怎么处理），系统做底层执行（静态路由、本地执行、结果标准化），配合记忆系统形成持续优化的闭环。**

最终方案：

```
Agent Loop 主循环（plan → execute → observe → replan）
+ Planner AI 决策（1~5 步，可重规划）
+ ToolRouter 静态映射（stepType → Tool，不让 AI 自由选底层工具）
+ Tool Adapter 模式（模块 Java Service + Adapter，编排层只依赖 Tool 接口）
+ 断点续跑（快照恢复状态 + Planner 续决策）
+ 子流程（BATCH/SUITE 在 ExecutionEngine 内部处理，V2 接 DAG）
```
