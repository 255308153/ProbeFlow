# 07-断言系统 PRD

## 1. 模块定位

断言系统是测试 Agent 执行引擎的核心子模块，负责把 HTTP 响应结果转成**稳定、可解释、可结构化**的判定结论。它回答："这次执行到底算通过还是失败，为什么。"

断言系统属于执行引擎内部，但作为独立模块设计，不耦合 HTTP 发送层。

```
TestCase (含 assertionDefinitions)
  + HttpResponse
  + ExecutionContext
  → AssertionPlanBuilder（构建断言计划）
  → AssertionEngine（主入口）
    → AssertionValueResolver（解析期望值中的变量）
    → AssertionDispatcher（按类型路由执行器）
    → AssertionExecutor（执行比较）
  → AssertionResultAggregator（按 severity 聚合）
  → AssertionSummary
```

**核心边界：断言系统负责"判定事实"，AI 负责"解释事实"。**

## 2. 核心设计原则

### 2.1 断言执行必须由本地代码完成

断言是事实判定，不适合依赖模型临时理解。需要稳定、低成本、高并发、结果一致、方便回放和调试。

### 2.2 断言对象必须结构化

断言定义不是自由文本，而是结构化对象：类型、操作符、目标、期望值、严重级别。

### 2.3 断言失败必须可解释

结果不能只返回 `true/false`。至少能说明：哪条断言失败了、期望值是什么、实际值是什么、失败发生在什么位置。

### 2.4 基础断言与业务断言分层

- **基础断言**：状态码、Header、Content-Type、响应时间 — 由系统自动补充
- **业务断言**：业务码、关键字段、业务状态 — 由 AI/人工定义

### 2.5 断言生成与断言执行分离

- **AssertionSuggestionGenerator（06 模块）**：决定"建议断言什么"，偏生成侧
- **AssertionPlanBuilder（07 模块）**：决定"本次执行到底用哪些断言"，偏执行前装配侧

`caseType → expectedStatus` 这类映射的权威来源是 `SystemAssertionRuleRegistry`（07 内部），06 可引用但以 07 为准。

### 2.6 断言期望值变量解析归断言系统内部

请求变量解析（VariableResolver）和断言期望值解析是两种语义，不混在一起：
- **VariableResolver**：负责请求体中 `${env.baseUrl}`、`${suite.token}` 的替换
- **AssertionValueResolver**：负责断言中 `${task.userId}`、`${step.createOrder.orderId}` 的替换

## 3. 整体失败策略（severity 聚合）

V1 直接支持按 severity 聚合，不走"一条失败即全失败"的一刀切。

### 3.1 聚合规则

| 条件 | 整体结论 |
|------|---------|
| 任意 `CRITICAL` 断言失败 | `FAILED` |
| 无 CRITICAL 失败，有 `WARNING` 失败 | `PASSED_WITH_WARNINGS` |
| 只有 `INFO` 失败或全部通过 | `PASSED` |

### 3.2 ExecutionResult 新增字段

```
overallStatus: PASSED / PASSED_WITH_WARNINGS / FAILED
criticalFailed: boolean
```

### 3.3 严重级别取值

| Severity | 含义 | 影响 |
|----------|------|------|
| `CRITICAL` | 关键断言，失败意味着核心功能异常 | 触发整体失败 |
| `WARNING` | 重要但非致命，如响应时间超标 | 整体通过但记警告 |
| `INFO` | 参考性断言，如字段值格式 | 不改变整体结论 |

## 4. 总架构

```
TestCase (assertionDefinitions[])
  + HttpResponse
  + ExecutionContext
  ↓
① AssertionPlanBuilder
  ├─ 读取 case 自带断言
  ├─ 按 caseType 查 SystemAssertionRuleRegistry 自动补基础断言
  ├─ 去重
  └─ 输出 AssertionPlan
  ↓
② AssertionEngine
  ├─ AssertionValueResolver（解析 expectedValue 中的 ${} 变量）
  ├─ AssertionDispatcher（按 assertionType 路由执行器）
  │    → StatusCodeAssertionExecutor
  │    → JsonPathAssertionExecutor（含数组支持）
  │    → HeaderAssertionExecutor
  │    → BodyTextAssertionExecutor
  │    → ResponseTimeAssertionExecutor
  └─ 输出 List<AssertionResult>
  ↓
③ AssertionResultAggregator
  ├─ 统计 total/passed/failed/warning
  ├─ 按 severity 判定 overallStatus
  └─ 输出 AssertionSummary
```

## 5. 模块拆分

| 组件 | 职责 |
|------|------|
| `AssertionPlanBuilder` | 构建本次执行断言计划（读 case 断言 + 补系统断言 + 去重） |
| `SystemAssertionRuleRegistry` | caseType → 基础断言映射的权威来源 |
| `AssertionEngine` | 主入口，协调整个断言执行流程 |
| `AssertionValueResolver` | 解析断言期望值中的 `${scope.path}` 和 `${fn.xxx()}` |
| `AssertionDispatcher` | 按 assertionType 路由到对应 Executor |
| `AssertionExecutor` | 通用执行器接口，每类断言一个实现 |
| `AssertionResultAggregator` | 按 severity 聚合多条断言结果 |
| `AssertionFailureFormatter` | 格式化失败信息（期望值、实际值、失败原因） |

## 6. 核心对象模型

### 6.1 AssertionDefinition

```java
public class AssertionDefinition {
    String assertionId;
    AssertionType type;          // STATUS_CODE / JSON_PATH / HEADER / BODY_TEXT / RESPONSE_TIME
    Operator operator;           // EQ / NE / IN / EXISTS / NOT_EXISTS / CONTAINS / NOT_CONTAINS / LT / LTE / GT / GTE
    String target;               // 断言目标（如 $.code、Content-Type、status）
    String expectedValue;        // 期望值（可含 ${} 变量表达式）
    Severity severity;           // CRITICAL / WARNING / INFO
    AssertionSource source;      // SYSTEM_GENERATED / AI_GENERATED / MANUAL / RULE_GENERATED
    String message;              // 断言失败时的自定义提示
}
```

### 6.2 AssertionPlan

```java
public class AssertionPlan {
    String executionId;
    String caseId;
    List<AssertionDefinition> assertions;  // 已去重的最终断言列表
    LocalDateTime generatedAt;
    Map<String, Object> metadata;
}
```

### 6.3 AssertionResult

```java
public class AssertionResult {
    String assertionId;
    AssertionType type;
    boolean passed;
    Severity severity;
    String target;
    Operator operator;
    String expectedValue;       // 变量解析后的最终期望值
    String actualValue;         // 从响应中取到的实际值
    String failureReason;       // 失败原因描述
    String message;
    AssertionSource source;
    long durationMs;
}
```

### 6.4 AssertionSummary

```java
public class AssertionSummary {
    int totalCount;
    int passedCount;
    int failedCount;
    int warningCount;
    boolean criticalFailed;              // 是否存在 CRITICAL 失败
    OverallStatus overallStatus;          // PASSED / PASSED_WITH_WARNINGS / FAILED
    List<AssertionResult> results;        // 所有单条断言结果
}
```

## 7. 第一版断言类型设计

| 类型 | 目标 | 第一版操作符 | 说明 |
|------|------|------------|------|
| `STATUS_CODE` | HTTP 状态码 | EQ, IN | 200, [401,403] |
| `JSON_PATH` | JSON 响应字段 | EQ, NE, EXISTS, NOT_EXISTS, IN, CONTAINS | 含数组 `[*]` 支持 |
| `HEADER` | 响应 Header | EXISTS, EQ, CONTAINS | traceId, Content-Type |
| `BODY_TEXT` | 文本响应 | CONTAINS, NOT_CONTAINS | 非 JSON 场景 |
| `RESPONSE_TIME` | 响应耗时 | LT, LTE | 1000ms |

## 8. JSONPath 数组支持（V1）

### 8.1 支持的能力

- 精确索引：`$.data.items[0].id` — 取数组中第 0 项的 id
- 通配选择：`$.data.items[*].id` — 取数组中所有项的 id（返回数组）
- 配套操作符：
  - `EXISTS`：路径存在且值非 null
  - `NOT_EXISTS`：路径不存在或值为 null
  - `CONTAINS`：路径值（数组）中包含期望值

### 8.2 通配数组断言示例

```json
// "列表里每一项的 id 都存在"
{
  "type": "JSON_PATH",
  "target": "$.data.items[*].id",
  "operator": "EXISTS",
  "severity": "CRITICAL"
}
```
效果：`$.data.items[*].id` 解析后得到一个数组 `[1, 2, 3]`，EXISTS 判断数组长度 > 0 且所有元素非 null。

### 8.3 V1 不支持

- 复杂谓词（"每一项都满足 x>10"）
- 嵌套 filter 表达式（`$..items[?(@.price>100)]`）
- 自定义 lambda 风格断言

## 9. SystemAssertionRuleRegistry（自动补断言）

### 9.1 定位

`caseType → 基础状态码断言` 映射的**唯一权威来源**。`AssertionPlanBuilder` 用它做最终补齐，`AssertionSuggestionGenerator`（06）可引用它做建议增强，但以 Registry 为准。

### 9.2 映射规则

| caseType | 自动补断言 | severity |
|----------|-----------|----------|
| `NORMAL` | STATUS_CODE = 200 | CRITICAL |
| `AUTH_FAILURE` | STATUS_CODE IN [401, 403] | CRITICAL |
| `REQUIRED_FIELD_MISSING` | STATUS_CODE = 400 | CRITICAL |
| `INVALID_TYPE` | STATUS_CODE = 400 | CRITICAL |
| `BOUNDARY_VALUE` | min/max 边界 → 200; min-1/max+1 → 400 | CRITICAL |
| `INVALID_ENUM` | STATUS_CODE = 400 | CRITICAL |
| `BUSINESS_RULE_EXCEPTION` | 不自动补（由 AI 断言覆盖） | — |
| `DEPENDENCY_EXCEPTION` | 不自动补（由 AI 断言覆盖） | — |

### 9.3 补充的基础断言（所有 case 通用）

除了 caseType 强相关的状态码断言外，所有 case 自动补充：

| 断言 | 规则 | severity |
|------|------|----------|
| Content-Type | 如果接口返回 JSON，断言 `Content-Type CONTAINS application/json` | INFO |
| 响应时间 | 如果 case 未指定 RESPONSE_TIME 断言，补充 `RESPONSE_TIME LT 5000`（默认 5s） | WARNING |

### 9.4 补充规则的来源标记

所有自动补充的断言标记 `source = SYSTEM_GENERATED`，便于后续分析断言效果。

## 10. AssertionPlanBuilder

### 10.1 职责

**本次执行到底用哪些断言**的最终决策者。统一做：读 case 断言 → 补系统断言 → 去重 → 形成 AssertionPlan。

### 10.2 构建流程

```
1. 从 TestCase.assertionDefinitions 读取 case 自带断言
2. 查 SystemAssertionRuleRegistry：
   a. 按 caseType 补状态码断言（如果 case 未自带同类型断言）
   b. 补通用基础断言（Content-Type、响应时间）
3. 去重：
   - 同 type + target + operator 的断言只保留一条
   - 手工/自定义断言优先于系统断言
4. 形成 AssertionPlan
```

### 10.3 去重规则

| 条件 | 行为 |
|------|------|
| 同 type + target + operator | 保留 source 优先级高的（MANUAL > AI_GENERATED > SYSTEM_GENERATED） |
| case 自带 RESPONSE_TIME 断言 | 不自动补 RESPONSE_TIME |
| case 自带 STATUS_CODE 断言 | 不自动补 STATUS_CODE |

## 11. AssertionValueResolver

### 11.1 定位

断言系统内部组件，负责在断言执行前解析 `expectedValue` 中的变量表达式。

### 11.2 解析时机

```
AssertionPlan 构建完成 → 断言执行前 → expectedValue 中的 ${} 被解析为实际值
```

### 11.3 解析来源

```
${env.xxx}     → 环境变量
${task.xxx}    → 任务级变量
${suite.xxx}   → 套件级变量  
${step.xxx}    → 步骤输出变量
${case.xxx}    → 用例级变量
${fn.xxx()}    → 动态函数
${data.xxx()}  → 测试数据函数
```

### 11.4 与 VariableResolver 的职责区分

| | VariableResolver（03-变量系统） | AssertionValueResolver（07-断言系统） |
|------|------|------|
| 使用时机 | 请求发出前 | 断言执行前 |
| 替换对象 | 请求模板中的变量 | 断言 expectedValue 中的变量 |
| 比较前处理 | 不需要 | 需要（类型转换、日期格式化） |

### 11.5 示例

```
断言定义: { target: "$.data.userId", operator: EQ, expectedValue: "${task.userId}" }
↓ AssertionValueResolver 解析
断言: { target: "$.data.userId", operator: EQ, expectedValue: "U12345" }
↓ 从响应取 actualValue
actualValue = JsonPath.read(response, "$.data.userId") → "U12345"
↓ 比较
"U12345".equals("U12345") → PASSED
```

## 12. 断言执行流程

### 12.1 完整流程

```
1. AssertionPlanBuilder.build(testCase, httpResponse)
   → 读 case 断言 + 补系统断言 + 去重 → AssertionPlan

2. 对 plan 中每条 assertionDefinition:
   a. AssertionValueResolver.resolve(expectedValue)
      → "${task.userId}" → "U12345"
   b. AssertionDispatcher.dispatch(assertionType)
      → StatusCodeAssertionExecutor / JsonPathAssertionExecutor / ...
   c. Executor.execute(operator, actualValue, expectedValue)
      - 从 httpResponse 取 actualValue（状态码、JSONPath、Header、Body、耗时）
      - 按 operator 比较
      - 返回 AssertionResult（passed, expectedValue, actualValue, failureReason）

3. AssertionResultAggregator.aggregate(results)
   → 统计 + severity 聚合 → AssertionSummary
```

### 12.2 各 Executor 比较逻辑

```java
// StatusCodeAssertionExecutor
EQ:   actual == expected
IN:   expectedList.contains(actual)

// JsonPathAssertionExecutor
EQ:         actual.equals(expected)
NE:         !actual.equals(expected)
EXISTS:     actual != null
NOT_EXISTS: actual == null
IN:         expectedList.contains(actual)
CONTAINS:   actual.toString().contains(expected.toString())

// HeaderAssertionExecutor
EXISTS:     headers.containsKey(target)
EQ:         headers.get(target).equals(expected)
CONTAINS:   headers.get(target).contains(expected)

// BodyTextAssertionExecutor
CONTAINS:     body.contains(expected)
NOT_CONTAINS: !body.contains(expected)

// ResponseTimeAssertionExecutor
LT:  actualMs < thresholdMs
LTE: actualMs <= thresholdMs
```

## 13. 失败结果格式

每条失败断言至少输出：

```text
{assertionType} assertion failed:
  target={target}
  expected={expectedValue}
  actual={actualValue}
  operator={operator}
  reason={failureReason}
```

**示例：**

```text
JSON_PATH assertion failed:
  target=$.code
  expected=0
  actual=1001
  operator=EQ
  reason=business code mismatch — expected 0 but got 1001
```

这样的格式可直接进入：
- 报告系统（展示给用户）
- AI 分析层（判断失败原因和下一步动作）
- 记忆系统（提取高频失败模式）

## 14. 与 06-用例生成模块的边界约定

| 责任 | 归属 | 说明 |
|------|------|------|
| "建议测什么 + 建议断言什么" | 06-AssertionSuggestionGenerator | 生成侧，输出 assertionSuggestions 写入 TestCase |
| "本次执行用哪些断言" | 07-AssertionPlanBuilder | 执行侧，读 case 断言 + 补系统断言 + 去重 |
| caseType → expectedStatus 映射 | 07-SystemAssertionRuleRegistry | 权威来源，06 可引用但以 07 为准 |

**06 和 07 各自维护各自的断言补充逻辑，不出现两套 caseType 映射。**

## 15. V1 操作符全集

| 操作符 | 适用类型 | 含义 |
|--------|---------|------|
| `EQ` | STATUS_CODE, JSON_PATH, HEADER | 等于 |
| `NE` | JSON_PATH | 不等于 |
| `IN` | STATUS_CODE, JSON_PATH | 值在列表中 |
| `EXISTS` | JSON_PATH, HEADER | 路径/字段存在且非空 |
| `NOT_EXISTS` | JSON_PATH | 路径/字段不存在或为空 |
| `CONTAINS` | JSON_PATH, HEADER, BODY_TEXT | 包含 |
| `NOT_CONTAINS` | BODY_TEXT | 不包含 |
| `LT` | RESPONSE_TIME | 小于 |
| `LTE` | RESPONSE_TIME | 小于等于 |
| `GT` | RESPONSE_TIME | 大于 |
| `GTE` | RESPONSE_TIME | 大于等于 |

## 16. Java 包结构建议

```
com.xxx.agent.assertion
com.xxx.agent.assertion.plan           // AssertionPlanBuilder, SystemAssertionRuleRegistry
com.xxx.agent.assertion.engine         // AssertionEngine
com.xxx.agent.assertion.executor       // AssertionExecutor 接口 + 5 个实现类
com.xxx.agent.assertion.dispatch       // AssertionDispatcher
com.xxx.agent.assertion.resolve        // AssertionValueResolver
com.xxx.agent.assertion.aggregate      // AssertionResultAggregator
com.xxx.agent.assertion.format         // AssertionFailureFormatter
com.xxx.agent.assertion.model          // AssertionDefinition, AssertionPlan, AssertionResult, AssertionSummary
```

## 17. 第一版落地范围

### 包含

- 5 类断言类型（STATUS_CODE / JSON_PATH / HEADER / BODY_TEXT / RESPONSE_TIME）
- 11 种操作符
- severity 聚合（CRITICAL → FAILED, WARNING → PASSED_WITH_WARNINGS）
- SystemAssertionRuleRegistry（caseType → 基础断言，唯一权威来源）
- AssertionPlanBuilder（读 case 断言 + 补系统断言 + 去重）
- AssertionValueResolver（断言内部变量解析，与 VariableResolver 分离）
- JSONPath 数组 `[*]` 基础支持 + EXISTS/CONTAINS
- AssertionFailureFormatter（结构化失败信息）
- 与 06 模块的清晰边界（断言建议在 06，断言执行在 07）

### 暂不包含

- 复杂逻辑组合断言（AND/OR/NOT 嵌套）
- 自定义脚本断言（Groovy/JavaScript）
- SQL 结果断言
- 外部系统回调断言
- 可视化断言编辑器
- JSONPath 复杂 filter 表达式

## 18. 一句话总结

断言系统的核心价值是：**把接口执行结果转成稳定、可解释、可结构化的判定结论，为执行引擎、报告系统和 AI 分析层提供可信的结果基础。**

最终方案：

```
severity 聚合 V1 就支持（不搞一刀切）
+ SystemAssertionRuleRegistry 作为自动补断言的唯一权威来源
+ AssertionPlanBuilder 统一装配（与 06 的 AssertionSuggestionGenerator 职责分离）
+ AssertionValueResolver 断言内部独立解析变量
+ JSONPath [*] 数组有限支持
+ 结构化失败结果（供报告/AI/记忆三层消费）
```
