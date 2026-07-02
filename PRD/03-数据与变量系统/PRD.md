# 03-数据与变量系统 PRD

## 1. 模块定位

数据与变量系统是测试 Agent 的执行期数据流转中间层，位于测试用例生成模块、执行引擎、编排系统之间。它解决的核心问题是：

**把单条测试用例变成可自动连续执行的测试流程。**

具体包括：
- 环境变量管理与多环境切换
- 鉴权数据注入
- 前置接口结果提取与传递
- 动态测试数据生成
- 变量作用域隔离与覆盖规则
- 执行链路变量快照与断点恢复

```
TestCase
  → VariableResolver（变量替换）
  → ExecutionContext（运行时上下文）
  → RequestBuilder（构建最终请求）
  → HttpExecutor（发送请求）
  → ResponseExtractor（提取响应变量）
  → ContextStore（回写上下文）
  → 下一条 TestCase
```

## 2. 设计原则

### 2.1 变量解析必须由本地代码完成

变量替换、响应提取、作用域管理由 Java 本地代码稳定实现，不依赖 AI 推理。原因：

- 需要稳定一致
- 需要低延迟（每次请求前都要做）
- 需要可追踪（哪个变量从哪来）
- 需要可重复执行

### 2.2 静态配置与执行期数据分离

| 类型 | 说明 | 示例 |
|------|------|------|
| 静态配置 | 环境地址、默认 Token、固定 Header | `baseUrl`, `apiKey` |
| 执行期数据 | 上一步返回的动态值 | `userId`, `orderId`, `accessToken` |

不允许两类数据混在一个 map 里。

### 2.3 变量必须有作用域

至少支持 4 层：`ENV → TASK → SUITE → STEP`。每一层都有明确的可见性规则和优先级。

### 2.4 变量必须可追踪来源

每个变量能回答：值从哪来、谁生成的、什么时候写入的。

### 2.5 第一版优先解决"接口链路能跑通"

不追求通用工作流变量平台，优先把接口自动化测试的关键数据链路做稳。

## 3. 变量表达式语法

### 3.1 标准语法

```
${scope.path}
```

**scope 取值：**

| scope | 说明 | 示例 |
|-------|------|------|
| `env` | 环境级变量 | `${env.baseUrl}` |
| `task` | 任务级变量 | `${task.userId}` |
| `suite` | 测试套件级变量 | `${suite.sharedToken}` |
| `step` | 步骤输出变量 | `${step.login.accessToken}` |
| `case` | 单条用例变量 | `${case.requestId}` |
| `fn` | 基础动态函数 | `${fn.uuid()}` |
| `data` | 测试数据函数 | `${data.randomEmail()}` |

### 3.2 路径能力

支持对象路径和简单数组下标：

```
${task.user.profile.id}             // 多级对象
${step.createOrder.items[0].id}     // 数组下标
```

### 3.3 第一版不支持

- 三元运算（`${cond ? a : b}`）
- 函数嵌套（`${fn.nowDate(${fn.format(...)})}`）
- 字符串拼接（`"prefix_${var}_suffix"` —— 非变量部分的表达式内拼接）
- 裸写变量名（`${token}`，不带命名空间前缀）

**强制要求：所有变量引用必须带命名空间前缀。** `${token}` 这种写法在第一版中不合法，解析器直接报错。

## 4. 变量作用域设计

### 4.1 作用域层级

```
ENV_SCOPE    (环境级，全局只读)
  └─ TASK_SCOPE   (任务级，本次任务全局可见)
       └─ SUITE_SCOPE  (套件级，当前 suite 内可见)
            └─ STEP_SCOPE  (步骤级，当前步骤或前置步骤输出可见)
                 └─ CASE_SCOPE (用例级，单条 case 局部)
```

### 4.2 解析优先级（由近到远覆盖）

| 优先级 | 作用域 | 说明 |
|--------|--------|------|
| 1 (最高) | `case` | 当前用例局部变量 |
| 2 | `step` (当前步骤命名空间) | 当前步骤或前置步骤输出 |
| 3 | `suite` | 当前测试套件共享变量 |
| 4 | `task` | 本次任务全局变量 |
| 5 (最低) | `env` | 环境配置变量 |

### 4.3 可见性规则

- `step` 变量只对**当前步骤和后续步骤**可见，不能向前引用
- `suite` 变量对所有步骤可见
- `task` 变量对整个任务可见
- `env` 变量对所有任务只读（不可在执行中修改）

### 4.4 变量覆盖规则

同名不同 scope 的变量，**近 scope 的值覆盖远 scope 的值**。覆盖时不修改源 scope 的值，仅在读取时按优先级取值。

每次覆盖记录审计日志：`[key, oldValue, newValue, sourceScope, stepId, timestamp]`

## 5. 变量来源设计

### 5.1 ENV（环境级变量）

**配置来源：** 环境配置文件 / 数据库配置表

**典型内容：**

| 变量 | 说明 |
|------|------|
| `baseUrl` | 测试环境地址 |
| `defaultToken` | 默认鉴权 Token |
| `tenantId` | 租户 ID |
| `appKey` | 应用 Key |
| `dbConnectionRef` | 数据库连接引用 |

**特点：** 与特定环境绑定，通常在任务开始前确定，执行中只读。

### 5.2 TASK（任务级变量）

**配置来源：** 用户输入 / 任务创建时指定

**典型内容：**

| 变量 | 说明 |
|------|------|
| `userId` | 业务输入的用户 ID |
| `account` | 本次任务使用的测试账号 |
| `targetModule` | 目标测试模块 |
| `custom.*` | 用户自定义参数 |

**特点：** 任务粒度，整个任务生命周期有效。

### 5.3 SUITE（套件级变量）

**配置来源：** 编排系统指定 / 前置步骤写入

**典型内容：**

| 变量 | 说明 |
|------|------|
| `sharedToken` | 登录后获取的共享 Token |
| `loginUserId` | 登录用户 ID |
| `batchOrderId` | 批次订单号 |

**特点：** 套件执行开始后产生，套件内所有步骤共享。

### 5.4 STEP（步骤输出变量）

**配置来源：** ResponseExtractor 从接口响应中提取

**典型内容：**

| 变量 | 说明 |
|------|------|
| `step.login.accessToken` | 登录接口返回的 Token |
| `step.createUser.userId` | 创建用户返回的 ID |
| `step.createOrder.orderId` | 创建订单返回的 ID |

**特点：** 接口自动化链路的**核心**变量类型。每个步骤的命名空间 = 步骤名，变量名自定义。

### 5.5 CASE（用例级变量）

**配置来源：** 单条 TestCase 定义

**典型内容：**

| 变量 | 说明 |
|------|------|
| `case.requestId` | 当前 case 的唯一请求标识 |
| `case.customParam` | case 级别的自定义参数 |

**特点：** 作用范围最小，常用于断言中的动态期望值。

### 5.6 动态函数

分为两层：

**基础函数（fn.*）：**

| 函数 | 说明 | 示例值 |
|------|------|---------|
| `${fn.uuid()}` | UUID | `a1b2c3d4-...` |
| `${fn.nowMillis()}` | 当前毫秒时间戳 | `1704067200000` |
| `${fn.nowDate()}` | 当前日期 | `2026-07-02` |
| `${fn.timestamp()}` | 当前秒级时间戳 | `1704067200` |
| `${fn.randomInt(min, max)}` | 范围内随机整数 | `573` |
| `${fn.randomString(length)}` | 指定长度随机字符串 | `aB3xK9` |
| `${fn.randomBoolean()}` | 随机布尔值 | `true` |
| `${fn.formatDate(pattern)}` | 格式化日期 | `2026-07-02 14:30:00` |
| `${fn.sequence(name)}` | 自增序列 | `ORDER_001` |

**测试数据函数（data.*）：**

| 函数 | 说明 | 示例值 |
|------|------|---------|
| `${data.randomEmail()}` | 随机邮箱 | `test_a1b2@example.com` |
| `${data.randomPhone()}` | 随机手机号 | `13800138000` |
| `${data.randomFrom(list)}` | 从列表中随机取 | `SUCCESS` |

## 6. 变量审计设计

### 6.1 VariableEntry

每条变量保存的元信息：

| 字段 | 类型 | 说明 |
|------|------|------|
| `key` | String | 变量名 |
| `value` | String | 当前值 |
| `sourceType` | Enum | 来源：`ENV` / `TASK` / `SUITE` / `STEP` / `CASE` / `DYNAMIC` |
| `scope` | Enum | 作用域 |
| `createdAt` | DateTime | 写入时间 |
| `updatedAt` | DateTime | 最后更新时间 |
| `createdByStep` | String | 写入该变量的 planStepId |
| `extractionPath` | String | 响应提取路径（如 `$.data.id`） |
| `overridden` | Boolean | 是否被更高优先级变量覆盖 |

### 6.2 审计价值

通过 VariableEntry 可以回答：
- 当前 Token 是哪一步写入的
- 为什么 userId 变了（被哪个后续步骤覆盖）
- 某条 case 用到的是新值还是旧值
- 某次执行中变量链路是否完整

## 7. ResponseExtractor（响应提取器）

### 7.1 提取来源

| 来源 | 说明 | 示例 |
|------|------|------|
| `BODY_JSON` | JSON 响应体字段 | `$.data.userId` |
| `HEADER` | 响应 Header | `Authorization`、`Set-Cookie` |
| `STATUS_CODE` | HTTP 状态码 | `200` |

### 7.2 ExtractionRule 定义

| 字段 | 类型 | 说明 |
|------|------|------|
| `ruleId` | String | 规则标识 |
| `sourceType` | Enum | `BODY_JSON` / `HEADER` / `STATUS_CODE` |
| `sourcePath` | String | 提取路径（JSONPath 或 Header Key）|
| `targetScope` | Enum | `TASK` / `SUITE` / `STEP` |
| `targetKey` | String | 写入的变量名 |
| `required` | Boolean | 是否为关键变量 |
| `failureStrategy` | Enum | 失败策略（见下方） |
| `defaultValue` | String | 默认值（failureStrategy=WRITE_DEFAULT 时生效）|

### 7.3 提取失败策略

| 策略 | 行为 |
|------|------|
| `FAIL_FAST` | 立即中断当前步骤，抛出异常 |
| `WRITE_NULL` | 写入 null 值，记录 warning，继续执行 |
| `WRITE_DEFAULT` | 写入 defaultValue，记录 warning，继续执行 |

### 7.4 失败时链路行为

| 条件 | 行为 |
|------|------|
| `required=true` + `FAIL_FAST` | **中断当前 SUITE**，标记失败 |
| `required=false` + `WRITE_NULL` | 继续执行后续步骤，记 warning |
| `required=false` + `WRITE_DEFAULT` | 写默认值后继续，记 warning |

**简化规则：** 关键变量提取失败 → 中断 SUITE；非关键变量提取失败 → 继续 + warning。

### 7.5 提取规则示例

```
行场景：登录 → 创建订单 → 查询订单

Step 1: login
  提取规则 1: sourceType=BODY_JSON, sourcePath="$.data.accessToken",
             targetScope=SUITE, targetKey="token", required=true,
             failureStrategy=FAIL_FAST

Step 2: createOrder
  提取规则 1: sourceType=BODY_JSON, sourcePath="$.data.orderId",
             targetScope=STEP, targetKey="createOrder.orderId", required=true,
             failureStrategy=FAIL_FAST
  提取规则 2: sourceType=HEADER, sourcePath="X-Trace-Id",
             targetScope=SUITE, targetKey="traceId", required=false,
             failureStrategy=WRITE_NULL

Step 3: queryOrder
  请求中引用: ${step.createOrder.orderId} 和 ${suite.token}
```

## 8. ExecutionContext 设计

### 8.1 对象定义

```java
public class ExecutionContext {
    String taskId;
    String environment;
    Map<String, VariableEntry> envVariables;      // 环境变量
    Map<String, VariableEntry> taskVariables;     // 任务级变量
    Map<String, VariableEntry> suiteVariables;    // 套件共享变量
    Map<String, VariableEntry> stepVariables;     // 步骤输出变量（key 带命名空间）
    AuthContext authContext;                       // 鉴权上下文
    Map<String, Object> runtimeFlags;             // 运行时标志（超时阈值等）
    Map<String, Object> metadata;                 // 扩展元数据
}
```

ExecutionContext 是 VariableResolver、RequestBuilder、AuthInjector、ResponseExtractor 之间的统一桥梁。

### 8.2 持久化策略

**原则：运行态用内存，恢复态用快照。**

| 数据 | 存储位置 | 说明 |
|------|---------|------|
| 执行中所有变量 | 内存（ExecutionContext） | 高频读写，不落库 |
| 关键节点快照 | PG 表 `execution_context_snapshot` | 用于断点恢复和事后审计 |

**快照时机：**

- Suite 开始执行时
- 每个关键步骤（required=true 的步骤）完成后
- Suite 执行完成 / 失败时

**快照内容（最小集合）：**

- 当前步骤输出变量
- 步骤内新增/覆盖的变量
- 恢复执行所需最小上下文
- 不落库的：中间 HTTP 原始响应体、全量工具输出

## 9. 变量解析流程

### 9.1 请求前替换流程

```
1. 读取请求模板中的变量表达式
2. VariableResolver 逐层解析：
   a. 检查能否匹配 ${scope.path} 格式
   b. 按优先级从 ExecutionContext 取值
   c. 如果是 fn.* / data.* → 调用对应函数
   d. 用解析后的值替换表达式
3. 输出最终值，填入 RequestBuilder
```

### 9.2 解析示例

**输入请求模板：**

```json
{
  "url": "${env.baseUrl}/api/orders/${step.createOrder.orderId}",
  "headers": {
    "Authorization": "Bearer ${suite.token}",
    "X-Request-Id": "${fn.uuid()}"
  },
  "body": {
    "userId": "${task.userId}",
    "email": "${data.randomEmail()}"
  }
}
```

**解析后：**

```json
{
  "url": "https://test.example.com/api/orders/ORD_20260702_001",
  "headers": {
    "Authorization": "Bearer eyJhbGci...",
    "X-Request-Id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  },
  "body": {
    "userId": "U12345",
    "email": "test_a1b2@example.com"
  }
}
```

### 9.3 错误处理

| 场景 | 行为 |
|------|------|
| 变量未定义 | 抛出 UndefinedVariableException，附带变量名和当前上下文摘要 |
| 作用域不存在 | 抛出 InvalidScopeException |
| 路径解析失败（如 JSONPath 无此字段） | 抛出 PathResolutionException |
| 函数调用失败（如参数错误） | 抛出 FunctionInvocationException |

## 10. DynamicValueProvider 函数定义

### 10.1 基础函数接口

```java
public interface DynamicValueProvider {
    String getName();                    // 函数名
    String getNamespace();               // fn 或 data
    String getSignature();               // 签名描述，如 "randomInt(min, max)"
    String execute(List<String> args);   // 执行函数
}
```

### 10.2 第一版内置函数清单

**fn 命名空间：**

| 函数 | 参数 | 返回值 |
|------|------|--------|
| `uuid` | 无 | UUID 字符串 |
| `nowMillis` | 无 | 毫秒时间戳 |
| `nowDate` | 无 | `yyyy-MM-dd` 格式日期 |
| `timestamp` | 无 | 秒级时间戳 |
| `randomInt` | min: int, max: int | 范围内随机整数 |
| `randomString` | length: int | 指定长度随机字母数字串 |
| `randomBoolean` | 无 | `true` 或 `false` |
| `formatDate` | pattern: string | 格式化当前日期 |
| `sequence` | name: string | 全局自增序号，格式 `NAME_001` |

**data 命名空间：**

| 函数 | 参数 | 返回值 |
|------|------|--------|
| `randomEmail` | 无 | 随机测试邮箱 |
| `randomPhone` | 无 | 随机有效格式手机号 |
| `randomFrom` | list: string (逗号分隔) | 列表中随机一项 |

## 11. 模块拆分

| 组件 | 职责 |
|------|------|
| `EnvironmentConfigManager` | 管理环境级配置（baseUrl、默认 Header、Token 等） |
| `ContextStore` | 运行时变量读写，按作用域查询，记录审计信息 |
| `ExecutionContextBuilder` | 合并多来源数据（env + task + suite + case + step）→ ExecutionContext |
| `VariableResolver` | 解析 `${scope.path}` 表达式，替换为实际值 |
| `DynamicValueProvider` | fn.* 和 data.* 函数注册与执行 |
| `ResponseExtractor` | 从 HTTP 响应中按 ExtractionRule 提取变量 |
| `VariableWriteBackService` | 将提取的变量回写到 ExecutionContext |
| `ScopeManager` | 管理作用域可见性与覆盖规则 |
| `VariableAuditService` | 记录变量变更审计日志 |

## 12. 第一版落地范围

**包含：**

- 7 类变量来源：env / task / suite / step / case / fn / data
- 4 层作用域 + 优先级覆盖规则
- 变量表达式 `${scope.path}` + 对象路径 + 简单数组下标
- 14 个内置动态函数（9 个 fn + 3 个 data）
- ResponseExtractor（BODY_JSON / HEADER / STATUS_CODE）
- 提取失败策略（FAIL_FAST / WRITE_NULL / WRITE_DEFAULT）+ required 字段
- ExecutionContext 内存执行 + 关键节点快照落库
- 变量审计（VariableEntry 全字段 + 覆盖日志）

**暂不包含：**

- 复杂表达式 DSL（三元运算、函数嵌套、字符串拼接）
- 裸写变量名兼容（`${token}` 不带前缀）
- 可视化变量编排平台
- 通用公式/脚本引擎
- 跨任务长期变量共享
- 复杂 Secrets 托管系统

## 13. Java 包结构建议

```
com.xxx.agent.variable
com.xxx.agent.variable.context       // ExecutionContext、ContextStore
com.xxx.agent.variable.resolve       // VariableResolver
com.xxx.agent.variable.extract       // ResponseExtractor、ExtractionRule
com.xxx.agent.variable.dynamic       // DynamicValueProvider、fn/data 函数实现
com.xxx.agent.variable.config        // EnvironmentConfigManager
com.xxx.agent.variable.scope         // ScopeManager
com.xxx.agent.variable.audit         // VariableAuditService、VariableEntry
com.xxx.agent.variable.model         // 变量系统对象模型
```

## 14. 一句话总结

数据与变量系统的核心价值是：**把环境配置、动态数据、接口响应和执行上下文串成一条稳定可追踪的数据链，让测试用例具备自动连续执行能力。**

最终方案：

```
${scope.path} 标准语法 + 4 层作用域 + 强制执行命名空间前缀
+ 14 个内置动态函数（fn/data 分层）
+ ResponseExtractor（required + failureStrategy 双字段决定链路行为）
+ 运行态内存 + 关键节点快照落库
+ 全链路变量审计
```
