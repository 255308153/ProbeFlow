# 08-接口测试执行引擎 PRD

## 1. 模块定位

接口测试执行引擎是测试 Agent 的执行子系统，负责把 `TestCase` 稳定地转换为真实 HTTP 请求并执行断言。它是整个测试平台中"真正干活"的模块，所有前面的分析、生成、编排，最终都通过执行引擎落地为可观测的执行结果。

**核心定位不是"一个 HTTP 工具类"，而是"测试 Agent 的标准执行子系统"。**

```
TestCase + ExecutionContext
  → ExecutionEngine
    → RequestBuilder → VariableResolver → AuthInjector → HttpExecutor
    → AssertionEngine → ResponseExtractor → VariableWriteBack
  → ExecutionResult / SuiteExecutionResult
```

## 2. 核心设计原则

### 2.1 执行引擎必须由本地代码实现

执行引擎使用本地 Java 代码实现，不用 AI 完成底层执行。原因：

- 需要稳定（同样的 case 多次执行结果一致）
- 需要低成本（每次请求走 LLM 不可接受）
- 需要高并发（批量执行不依赖模型吞吐）
- 需要易调试（请求/响应过程可追踪）
- 需要可控（超时、重试、SSL、代理精确控制）

### 2.2 AI 负责决策，执行引擎负责执行

```
AI 决定：测什么、怎么测、何时执行、结果怎么解读
执行引擎负责：把这些决策稳定落地为真实请求和断言结果
```

### 2.3 执行引擎是系统，不是工具类

至少包含：请求构建、变量解析、鉴权注入、HTTP 执行、断言执行、结果标准化、执行记录持久化。

### 2.4 编排层与执行层的清晰边界

- **编排层**：决定执行哪个 suite、哪些 case、什么顺序、失败后怎么处理
- **执行引擎**：负责把给定的一组步骤稳定跑完，管理步骤间的数据流和异常
- 执行引擎不负责"要不要重跑整个 suite"，那是编排层的决策

## 3. 执行模式设计

### 3.1 三种模式

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| `SINGLE` | 执行一个接口测试用例 | 单条验证、调试、手工触发 |
| `SUITE` | 按顺序串行执行一组有关联的接口用例 | 链路测试（登录→下单→支付）|
| `BATCH` | 并行执行一组独立接口用例 | 批量回归、Smoke 测试 |

### 3.2 SINGLE 模式

```
输入: ExecutionTask (1 个 case)
流程: RequestBuilder → VariableResolver → AuthInjector → HttpExecutor → AssertionEngine → ResultCollector
输出: ExecutionResult
```

### 3.3 SUITE 模式

```
输入: SuiteExecutionTask (N 个 case, 有序)
流程:
  ExecutionContext ctx = contextBuilder.build(task)
  for each case in suite.cases:
    1. RequestBuilder.build(case, ctx)
    2. VariableResolver.resolve(request, ctx)
    3. AuthInjector.inject(request, ctx)
    4. response = HttpExecutor.execute(request)
    5. assertionSummary = AssertionEngine.execute(case, response)
    6. result = ResultCollector.collect(case, request, response, assertionSummary)
    7. ResponseExtractor.extract(response, case.extractRules) → variables
    8. VariableWriteBackService.writeBack(variables, ctx)  // 更新 ctx，下一步可见
    9. results.add(result)
    if (result.failed && failStrategy == FAIL_FAST) break
输出: SuiteExecutionResult (含所有步骤明细)
```

**关键设计：SUITE 步骤间通过同一个 ExecutionContext 串联，执行引擎内部闭环。** 编排层提交 SuiteExecutionTask，拿最终 SuiteExecutionResult，中间步骤的上下文更新对编排层透明。

### 3.4 BATCH 模式

```
输入: BatchExecutionTask (N 个独立 case)
流程:
  为每个 case 创建独立的 ExecutionContext（只读共享 env/task 配置，不共享可写 scope）
  并行执行 N 个 SINGLE
输出: BatchExecutionResult (含所有 case 的 ExecutionResult[])
```

## 4. 执行上下文隔离规则

### 4.1 上下文分类

| 上下文类型 | SUITE 串行 | BATCH 并行 | 说明 |
|-----------|:---------:|:---------:|------|
| env（环境配置） | 只读共享 | 只读共享 | 所有 case 可读 |
| task（任务级变量） | 只读共享 | 只读共享 | 所有 case 可读 |
| suite（套件共享变量） | **可读可写** | **只读共享** | 串行可累积，并行禁止写 |
| step（步骤输出） | 可读可写 | 独立隔离 | 每个 case 独立的 step scope |
| case（用例级变量） | 独立 | 独立 | 每个 case 独立的 case scope |

### 4.2 并行安全规则

```
规则 1: BATCH 模式下，每个 case 获得独立的 ExecutionContext 副本
规则 2: BATCH 模式下，禁止往共享 suite scope 写入
规则 3: 如果 case 的 extractRules 声明了 targetScope=SUITE，在 BATCH 模式下自动降级为 targetScope=STEP（仅本 case 可见）
规则 4: SUITE 模式下，suite scope 写入是安全的（串行保证）
```

## 5. 模块拆分

| 组件 | 职责 |
|------|------|
| `ExecutionEngine` | 主入口，协调整个执行流程。支持 SINGLE / SUITE / BATCH 三种模式 |
| `RequestBuilder` | 把 ApiSpec + TestCase + ExecutionContext 转换成最终 HTTP 请求对象 |
| `VariableResolver` | 解析请求中的 `${scope.path}` 变量表达式（复用 03-数据与变量系统） |
| `AuthInjector` | 将认证配置注入请求（Bearer Token / API Key / Basic Auth / Header） |
| `HttpExecutor` | 真正发送 HTTP 请求，管理连接池、超时、SSL、代理 |
| `AssertionEngine` | 执行断言并输出结构化断言结果（复用 07-断言系统） |
| `ResponseExtractor` | 从 HTTP 响应中按 ExtractionRule 提取变量（复用 03-数据与变量系统） |
| `VariableWriteBackService` | 将提取的变量回写到 ExecutionContext（复用 03-数据与变量系统） |
| `ResultCollector` | 把原始请求/响应/断言结果转换成标准化 ExecutionResult |
| `ExecutionRecorder` | 执行结果持久化到 PostgreSQL |
| `ExecutionContextManager` | 管理执行上下文创建、隔离、更新 |

## 6. 核心对象模型

### 6.1 ExecutionTask（执行任务单元）

```java
public class ExecutionTask {
    String taskId;
    String executionId;
    ExecutionMode mode;           // SINGLE / SUITE / BATCH
    ApiSpec apiSpec;
    TestCase testCase;            // SINGLE 模式
    SuiteExecutionConfig suiteConfig;  // SUITE 模式
    BatchExecutionConfig batchConfig;  // BATCH 模式
    ExecutionContext baseContext;      // 初始上下文（env + task + suite 初始值）
    ExecutionOptions options;          // 超时、重试、SSL、代理等
}
```

### 6.2 SuiteExecutionConfig

执行引擎直接从 TestCase.steps[]（TestCaseStep[]）读取步骤定义运行。**不单独建模 SuiteStep**——TestCaseStep 是持久化层和执行层的统一概念（定义见 01-PRD 4.4 节）。

```java
public class SuiteExecutionConfig {
    String testCaseId;                     // SUITE 模式对应的 TestCase ID
    List<TestCaseStep> steps;              // 直接来自 TestCase.steps
    FailStrategy failStrategy;             // FAIL_FAST / CONTINUE_ON_FAILURE / CONTINUE_IF_NON_CRITICAL
}
```

TestCaseStep（运行时读取，持久化定义在 01-PRD）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `order` | Integer | 步骤序号 |
| `stepName` | String | 步骤名（用作 step scope 命名空间） |
| `apiSpecId` | String | 关联的 ApiSpec ID |
| `requestTemplate` | JSONB | 请求模板 |
| `assertionDefinitions` | AssertionDefinition[] | 断言定义 |
| `extractRules` | ExtractionRule[] | 响应提取规则 |
| `critical` | Boolean | 关键步骤标记 |

### 6.3 SuiteExecutionResult

```java
public class SuiteExecutionResult {
    String suiteId;
    String taskId;
    OverallStatus overallStatus;         // PASSED / PASSED_WITH_WARNINGS / FAILED
    List<StepExecutionDetail> stepDetails;
    ExecutionContext finalContext;        // 执行结束时的上下文（变量快照）
    long totalDurationMs;
}

public class StepExecutionDetail {
    int stepOrder;
    String stepName;
    StepStatus status;             // EXECUTED / FAILED / SKIPPED
    ExecutionResult executionResult;  // 单步详细结果（仅 EXECUTED/FAILED 时有值）
    String skipReason;                // 跳过的原因（仅 SKIPPED 时有值）
}
```

### 6.4 ExecutionResult（单次执行结果）

```java
public class ExecutionResult {
    String executionId;
    String taskId;
    String caseId;
    String stepId;
    ExecutorType executorType;     // HTTP
    String environment;             // dev / test / staging
    RequestSnapshot requestSnapshot;
    ResponseSnapshot responseSnapshot;
    AssertionSummary assertionSummary;  // 来自 07-断言系统
    OverallStatus overallStatus;         // PASSED / PASSED_WITH_WARNINGS / FAILED
    long durationMs;
    Integer statusCode;
    String errorMessage;            // 异常信息（超时、连接失败等）
    LocalDateTime createdAt;
}
```

### 6.5 BatchExecutionResult

```java
public class BatchExecutionResult {
    String batchId;
    String taskId;
    int totalCount;
    int passCount;
    int failCount;
    int warningCount;
    List<ExecutionResult> results;   // 所有 case 结果
    long totalDurationMs;
}
```

## 7. 主执行流程

### 7.1 SINGLE 流程

```
1. ExecutionContext ctx = ExecutionContextManager.create(baseContext, caseId)
2. RequestBuilder.build(apiSpec, testCase, ctx)
   → 组装 URL、Query、Header、Body
3. VariableResolver.resolve(request, ctx)
   → 替换 ${env.baseUrl}、${suite.token}、${fn.uuid()} 等
4. AuthInjector.inject(request, ctx)
   → 注入 Bearer Token / API Key / Basic Auth
5. response = HttpExecutor.execute(request)
   → 发送 HTTP 请求，获取状态码 + Header + Body + 耗时
6. assertionSummary = AssertionEngine.execute(testCase, response)
   → 执行断言计划，按 severity 聚合（复用 07-断言系统）
7. result = ResultCollector.collect(case, request, response, assertionSummary)
   → 构建 ExecutionResult
8. variables = ResponseExtractor.extract(response, testCase.detail.extractRules)
   → 从响应中提取变量
9. VariableWriteBackService.writeBack(variables, ctx)
   → 将变量写回 ctx（SINGLE 模式这一步对后续无影响，但保持流程完整）
10. ExecutionRecorder.save(result)
    → 持久化到 PostgreSQL
11. 返回 ExecutionResult
```

### 7.2 SUITE 流程

```
1. ExecutionContext ctx = ExecutionContextManager.create(baseContext, suiteId)
   → 初始上下文包含 env、task、suite 初始只读变量
2. List<StepExecutionDetail> details = []
3. for each step in suiteConfig.steps:
   a. ctx = ExecutionContextManager.stepScope(ctx, step.stepName)
      → 创建当前步骤的 step scope 命名空间
   b. 执行 SINGLE 流程（步骤 2-10），ctx 传入并在步骤 9 更新
   c. detail = StepExecutionDetail { order, name, EXECUTED, result }
   d. details.add(detail)
   e. if (result.overallStatus == FAILED):
        if step.critical && failStrategy == FAIL_FAST:
          剩余步骤标 SKIPPED，break
        if failStrategy == CONTINUE_ON_FAILURE:
          continue
        if failStrategy == CONTINUE_IF_NON_CRITICAL && step.critical:
          剩余步骤标 SKIPPED，break
4. overallStatus = 聚合所有 step detail 的 status
5. return SuiteExecutionResult { suiteId, taskId, overallStatus, details, ctx, duration }
```

### 7.3 BATCH 流程

```
1. List<CompletableFuture<ExecutionResult>> futures = []
2. for each case in batchConfig.cases:
   future = CompletableFuture.supplyAsync(() -> {
     ExecutionContext isolatedCtx = ExecutionContextManager.createIsolated(baseContext, caseId)
     // isolatedCtx 只共享只读的 env/task，suite scope 为空
     return executeSingle(apiSpec, case, isolatedCtx)  // 执行 SINGLE 流程
   })
   futures.add(future)
3. List<ExecutionResult> results = CompletableFuture.allOf(futures).join()
4. return BatchExecutionResult { batchId, taskId, totalCount, passCount, failCount, results, duration }
```

## 8. HttpExecutor 设计

### 8.1 技术选型

使用 **OkHttp** 作为底层 HTTP 客户端。

| 特性 | 配置 |
|------|------|
| 客户端实例 | **单例** `OkHttpClient`，不每次新建 |
| 连接池 | 启用默认连接池（最大空闲连接 5，保持 5 分钟） |
| HTTP/2 | 默认开启 |
| 请求压缩 | 默认开启 gzip |

**不要每次请求 new OkHttpClient**，否则连接复用失效、TLS 握手浪费、性能差。

### 8.2 HttpExecutor 职责

```
- 发起 HTTP 请求（GET/POST/PUT/DELETE/PATCH）
- 处理超时（connect/read/write 三层）
- 处理连接异常（connection reset、socket timeout）
- 获取响应：状态码、Header、Body、耗时
- 日志记录（请求/响应摘要，不含敏感 Header）
```

### 8.3 HttpClientConfig

```java
public class HttpClientConfig {
    // 超时配置
    Duration connectTimeout;      // 默认 5s
    Duration readTimeout;          // 默认 30s
    Duration writeTimeout;         // 默认 15s
    Duration callTimeout;          // 整体调用超时，默认 60s

    // 连接池配置
    int maxIdleConnections;        // 默认 5
    Duration keepAliveDuration;    // 默认 5 分钟

    // SSL 配置
    SslMode sslMode;               // STRICT / INSECURE_TEST_ONLY
    String trustStorePath;         // 自定义 trust store（可选）
    String trustStorePassword;     // trust store 密码（可选）

    // 代理配置
    ProxyConfig proxy;             // HTTP/HTTPS 代理（可选）

    // 重试配置
    RetryPolicy retryPolicy;
}
```

### 8.4 SSL 模式

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| `STRICT` | 标准证书校验 | 生产环境、正式测试环境 |
| `INSECURE_TEST_ONLY` | 信任所有证书 | 自签证书测试环境 |

**`INSECURE_TEST_ONLY` 必须是环境级配置，不能默认全局关闭。** 在配置中显式标注 `insecureTestOnly` 字样以提醒风险。

### 8.5 代理支持

```java
public class ProxyConfig {
    boolean enabled;
    ProxyType type;        // HTTP / SOCKS
    String host;
    int port;
    String username;       // 可选
    String password;       // 可选
    List<String> nonProxyHosts;  // 不走代理的主机列表
}
```

## 9. 超时与重试设计

### 9.1 超时分层

```
全局默认 → 接口级覆盖 → case 级覆盖
```

| 层级 | 配置来源 | 示例 |
|------|---------|------|
| 全局默认 | `application.yml` | connectTimeout=5s, readTimeout=30s |
| 接口级 | ApiSpec / 环境配置 | 某导出接口 readTimeout=120s |
| case 级 | TestCase.detail 或 ExecutionOptions | 某条特殊 case 单独设超时 |

### 9.2 第一版默认超时值

| 超时类型 | 默认值 | 说明 |
|---------|--------|------|
| `connectTimeout` | 5s | TCP 连接建立超时 |
| `readTimeout` | 30s | 等待响应数据超时 |
| `writeTimeout` | 15s | 发送请求体超时 |
| `callTimeout` | 60s | 整个 HTTP 调用总超时 |

### 9.3 重试策略

**默认 maxRetries = 1。**

**只重试基础设施类错误：**

| 可重试 | 说明 |
|--------|------|
| `SocketTimeoutException` | 读超时 |
| `ConnectTimeoutException` | 连接超时 |
| `ConnectionResetException` | 连接被重置 |
| HTTP 502 / 503 / 504 | 网关/服务临时不可用（可选，配置开关） |

**不重试：**

| 不重试 | 说明 |
|--------|------|
| HTTP 4xx | 客户端错误，重试无意义 |
| 断言失败 | 业务逻辑问题，不是网络问题 |
| 业务码错误 | 参数或业务规则问题 |

### 9.4 退避策略

```
第一版：指数退避 + 随机抖动
  第 1 次重试：500ms + random(0, 200ms)
  第 2 次重试：1000ms + random(0, 500ms)
  （如果配置了 maxRetries > 1）
```

### 9.5 RetryPolicy 配置

```yaml
http:
  retry:
    maxRetries: 1
    retryOnTimeout: true
    retryOnConnectionError: true
    retryOnServerError: false     # 502/503/504，默认不重试
    backoff:
      initialDelayMs: 500
      multiplier: 2.0
      maxDelayMs: 5000
      jitter: true
```

## 10. SUITE 失败策略

### 10.1 三种策略

| 策略 | 行为 |
|------|------|
| `FAIL_FAST`（默认） | 任何步骤失败立即停止 suite，后续步骤标记 SKIPPED |
| `CONTINUE_ON_FAILURE` | 某步失败后继续执行剩余步骤 |
| `CONTINUE_IF_NON_CRITICAL` | 非关键步骤失败继续，关键步骤失败停止 |

### 10.2 步骤关键性标记

每条 SuiteStep 带 `critical: boolean`：

- `critical=true`（默认）：该步骤失败且策略为 FAIL_FAST 或 CONTINUE_IF_NON_CRITICAL 时，停止 suite
- `critical=false`：该步骤失败不影响后续步骤（如 traceId 提取失败不影响业务流程）

### 10.3 SUITE 失败后的状态标记

```
Step 1: login          → EXECUTED, PASSED
Step 2: createOrder    → FAILED, status=500
Step 3: queryOrder     → SKIPPED, reason="前序关键步骤 createOrder 失败"
Step 4: updateOrder    → SKIPPED, reason="前序关键步骤 createOrder 失败"
```

编排层拿到 SuiteExecutionResult 后可以清楚看到：已执行、失败、跳过的完整情况。

## 11. AuthInjector 设计

### 11.1 支持的鉴权方式

| 方式 | 配置 | 注入位置 |
|------|------|---------|
| Bearer Token | `Authorization: Bearer ${suite.token}` | Header |
| API Key | `X-API-Key: ${env.apiKey}` | Header |
| Basic Auth | `Authorization: Basic base64(user:pass)` | Header |
| Custom Header | 任意键值对 | Header |
| Query Token | `?token=${env.token}` | Query 参数 |

### 11.2 鉴权来源

```
1. TestCase.detail 中指定的 auth 配置
2. ApiSpec.auth 中提取的鉴权要求
3. EnvironmentConfig 中的默认 Token/Key
4. ExecutionContext 中前置步骤注入的 Token（如 ${suite.token}）
```

### 11.3 AuthContext

```java
public class AuthContext {
    AuthType type;               // BEARER / API_KEY / BASIC / CUSTOM_HEADER / QUERY_TOKEN / NONE
    String tokenKey;             // Header 名称或 Query 参数名
    String tokenValue;           // Token 值（可含 ${} 变量）
    String tokenSource;          // ENV / SUITE / CASE / MANUAL
}
```

## 12. 与各模块的关系

| 模块 | 关系 |
|------|------|
| 03-数据与变量系统 | 复用 VariableResolver、ResponseExtractor、VariableWriteBackService |
| 07-断言系统 | 复用 AssertionEngine、AssertionPlanBuilder |
| 06-测试用例生成 | 消费 TestCase（含 ApiTestCaseDetail、assertionDefinitions、extractRules） |
| 01-编排系统 | 接收 ExecutionTask，返回 ExecutionResult / SuiteExecutionResult |
| 02-记忆系统 | ExecutionRecorder 写入结果，供记忆系统提纯消费 |
| 数据模型 | ExecutionRecord、Observation 按 01-PRD 定义的模型落库 |

## 13. 执行记录持久化

### 13.1 ExecutionRecorder 职责

- 每次执行完成（无论成功/失败/超时），写入 ExecutionRecord
- 记录请求快照、响应快照、断言结果摘要
- 敏感信息脱敏（Authorization Header 值替换为 `***`）

### 13.2 写入时机

```
SINGLE: 每次执行完立即写入
SUITE: 每步执行完写入单步记录 + suite 完成写入 SuiteExecutionRecord
BATCH: 每个 case 执行完写入各自记录
```

## 14. Java 包结构建议

```
com.xxx.agent.execution
com.xxx.agent.execution.engine        // ExecutionEngine（主入口）
com.xxx.agent.execution.builder       // RequestBuilder
com.xxx.agent.execution.variable      // VariableResolver（复用 03）
com.xxx.agent.execution.auth           // AuthInjector, AuthContext
com.xxx.agent.execution.http           // HttpExecutor, HttpClientConfig
com.xxx.agent.execution.assertion      // AssertionEngine（复用 07）
com.xxx.agent.execution.extract       // ResponseExtractor（复用 03）
com.xxx.agent.execution.result        // ResultCollector, ExecutionRecorder
com.xxx.agent.execution.context       // ExecutionContextManager（上下文隔离）
com.xxx.agent.execution.model         // ExecutionTask, ExecutionResult, SuiteExecutionResult, BatchExecutionResult
com.xxx.agent.execution.config        // HttpClientConfig, RetryPolicy, ProxyConfig
```

## 15. 第一版落地范围

### 包含

- SINGLE / SUITE / BATCH 三种执行模式
- SUITE 内部闭环（步骤间通过 ExecutionContext 串联）
- BATCH 并行上下文隔离（不共享可写 suite scope）
- OkHttp 单例 + 连接池 + HTTP/2
- 三层超时（connect/read/write/call）+ 可配置重试（仅网络/临时服务异常）
- 指数退避 + 抖动
- SUITE 失败策略（FAIL_FAST / CONTINUE_ON_FAILURE / CONTINUE_IF_NON_CRITICAL）
- 步骤关键性标记（critical）
- 5 种鉴权方式（Bearer / API Key / Basic / Custom Header / Query Token）
- SSL 双模式（STRICT / INSECURE_TEST_ONLY）+ 自定义 trust store
- HTTP/HTTPS 代理支持
- 敏感信息脱敏（Authorization Header 等）
- 请求/响应 Body 大小限制（默认 10MB，防止 OOM）
- 执行记录持久化

### 暂不包含

- WebSocket / gRPC 协议支持
- 文件上传（multipart/form-data）高级处理
- OAuth 2.0 自动刷新 Token
- 自定义签名算法插件
- 响应缓存
- 流量录制与回放

## 16. 一句话总结

接口测试执行引擎的核心不是"发请求"，而是：**把 TestCase 稳定地转换为真实 HTTP 执行，管理步骤间的数据流与上下文隔离，产出可被编排系统、记忆系统、报告系统统一消费的标准执行结果。**

最终方案：

```
SINGLE / SUITE / BATCH 三种模式
+ SUITE 内部闭环（ExecutionContext 串联，编排层拿最终汇总）
+ BATCH 并行上下文隔离（不共享可写 scope）
+ OkHttp 单例 + 连接池 + SSL/代理可配置
+ 分层超时 + 仅重试网络异常 + 指数退避抖动
+ FAIL_FAST 默认 + CONTINUE 可选
+ 5 种鉴权 + 敏感信息脱敏
```
