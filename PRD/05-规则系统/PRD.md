# 05-规则系统 PRD

## 1. 模块定位

规则系统是测试用例生成模块的底层引擎，负责把 `ApiSpec` 中的结构化字段约束，**稳定、可解释、可批量**地翻译为基础测试点。它不是替代 AI，而是保证接口测试的底层覆盖率。

```
ApiSpec
  → ConstraintExtractor → RuleContext
  → BaseValidRequestBuilder（合法请求骨架）
  → RuleEngine → RuleMatcher → RuleExecutor → ValueFactory
  → DraftDeduplicator → TestCaseDraft[]
  → CaseNormalizer → TestCase[]
```

**核心边界：规则系统解决"基础覆盖"，不是解决"全部测试智能化"。**

## 2. 核心设计原则

### 2.1 规则定义与规则执行分离

- **规则定义阶段**：人工设计，必要时由 AI 辅助（辅助设计规则，不是运行时替代）
- **规则执行阶段**：由本地 Java 代码稳定执行，不依赖 AI 推理

**AI 在规则系统中的角色是辅助设计规则，而不是在运行时替代规则引擎。** 这是整个测试 Agent 架构里非常重要的一条边界。

### 2.2 规则必须可解释

每条生成的 case 都能回答：
- 来自哪条规则（ruleCode）
- 命中了哪个字段（targetField）
- 为什么生成这条 case（matchCondition）

### 2.3 规则输出必须结构化

规则系统输出标准 `TestCaseDraft`，不是自然语言描述。每个 draft 包含：`caseType`、`title`、`description`、`targetField`、`inputPatch`、`expectedStatusCode`、`ruleCode`。

### 2.4 规则优先覆盖高价值通用场景

第一版规则覆盖：正常请求、必填缺失、类型错误、边界值、非法枚举、鉴权缺失。不试图把所有业务复杂性塞进规则系统。

## 3. 总架构

```
ApiSpec
  → ConstraintExtractor（字段约束提取）
  → RuleContextBuilder（规则上下文构建）
  → BaseValidRequestBuilder（合法请求骨架构建）  ← 前置组件
  → RuleEngine
      ├─ RuleMatcher（规则匹配）
      ├─ RuleExecutor（规则执行）
      └─ ValueFactory（测试值生成）
  → DraftDeduplicator（结构去重）
  → TestCaseDraft[]
```

## 4. 模块拆分

| 组件 | 职责 |
|------|------|
| `ConstraintExtractor` | 从 ApiSpec 提取统一字段约束 |
| `RuleContextBuilder` | 构造 RuleContext（接口信息 + 约束列表 + 鉴权要求 + 合法请求） |
| `BaseValidRequestBuilder` | 按优先级链构建可稳定生成的合法请求骨架 |
| `RuleEngine` | 主入口，协调规则匹配→执行→聚合 |
| `RuleMatcher` | 判断规则是否适用于当前接口/字段 |
| `RuleExecutor` | 执行规则逻辑，产出 TestCaseDraft |
| `ValueFactory` | 为规则稳定生产合法值/非法值/边界值/空值 |
| `DraftDeduplicator` | 结构级去重与裁剪 |

## 5. 核心对象模型

### 5.1 FieldConstraint

```java
public class FieldConstraint {
    String fieldPath;        // body.user.name
    String fieldName;        // name
    FieldLocation location;  // PATH / QUERY / HEADER / BODY
    String dataType;         // string / int / long / boolean / enum
    Boolean required;
    Boolean nullable;
    List<String> enumValues;
    Long minValue;
    Long maxValue;
    Integer minLength;
    Integer maxLength;
    String pattern;
    String format;           // email, date, uuid
    String exampleValue;     // 从文档/代码注释提取
    String defaultValue;     // 从 @RequestParam(defaultValue=...) 提取
}
```

### 5.2 RuleContext

```java
public class RuleContext {
    String apiId;
    String apiName;
    String httpMethod;
    String path;
    Boolean authRequired;
    List<FieldConstraint> parameterConstraints;  // Query/Path/Header 参数
    List<FieldConstraint> bodyConstraints;       // Body 字段
    Map<String, Object> baseValidRequest;        // BaseValidRequestBuilder 产出
    GenerationConfig generationConfig;           // YAML 配置
}
```

### 5.3 TestCaseDraft

```java
public class TestCaseDraft {
    String draftId;
    CaseType caseType;        // NORMAL, REQUIRED_FIELD_MISSING, ...
    String title;
    String description;
    String ruleCode;           // 来源规则
    String targetField;        // 命中的字段路径
    Map<String, Object> inputPatch;  // 相对 baseValidRequest 的改动
    Integer expectedStatusCode;      // 200, 400, 401...
    String expectedErrorCode;        // 可选
    List<String> tags;
    Integer priority;          // 字段风险优先级
}
```

### 5.4 RuleDefinition

```java
public interface RuleDefinition {
    String getRuleCode();
    String getRuleName();
    CaseType getSupportedCaseType();
    RuleLevel getLevel();          // API_LEVEL / FIELD_LEVEL
    boolean match(RuleContext ctx, FieldConstraint field);
    List<TestCaseDraft> execute(RuleContext ctx, FieldConstraint field);
}
```

## 6. BaseValidRequestBuilder（前置组件）

### 6.1 定位

`BaseValidRequestBuilder` 是规则系统的**前置组件**，输出供所有字段变异规则复用的合法请求基线。它是规则引擎运行的前置条件——没有合法请求骨架，字段变异规则无法生成有意义的 `inputPatch`。

### 6.2 值来源优先级链

```
1. exampleValue       （从文档/代码注释提取，最贴近真实业务）
2. defaultValue       （从 @RequestParam(defaultValue=...) 提取）
3. 知识库/记忆系统项目默认值  （如已知 tenantId=1000、bizType=ORDER）
4. ValueFactory 模板值       （稳定兜底）
5. AI 增强补全        （仅在特定字段明显缺乏业务意义时启用，按需）
```

**核心原则：默认由本地代码生成一个可执行合法请求骨架，AI 只做增强不做硬依赖。**

### 6.3 产出示例

```json
{
  "userId": "U12345",        // 来自知识库推荐值
  "amount": 100,              // 来自 ValueFactory 模板
  "bizType": "ORDER",         // 来自知识库推荐值
  "pageNo": 1,                // 来自 defaultValue
  "pageSize": 10              // 来自 defaultValue
}
```

## 7. ValueFactory

### 7.1 定位

ValueFactory 是规则系统的关键基础设施，不负责业务推理，只为规则稳定生产测试值。

### 7.2 值生成方法

| 方法 | 说明 | 示例产出 |
|------|------|---------|
| `createValidValue(type)` | 类型合法值 | string→"test_value", int→100 |
| `createMissingValueMarker()` | 缺失标记 | `※MISSING※` |
| `createInvalidTypeValue(type)` | 类型非法值 | string→12345, int→"abc" |
| `createBoundaryValues(constraint)` | 边界值列表 | min, max, min-1, max+1 |
| `createInvalidEnumValue(enumValues)` | 非法枚举值 | "INVALID_ENUM_VALUE" |
| `createEmptyValue(type)` | 空值/null | null, "", [] |

### 7.3 值生成原则

- 可复用：同一字段类型复用同一套生成逻辑
- 可解释：每条值能回答"为什么是这个值"
- 尽量稳定：不引入随机值，支持重复执行
- 不引入额外业务噪音：非法值使用 `INVALID_ENUM_VALUE`、`12345` 等明确标记

## 8. 内置规则定义

### 8.1 规则分类

| 级别 | 作用范围 | 示例 |
|------|---------|------|
| 接口级（API_LEVEL） | 整个接口 | NORMAL_REQUEST, AUTH_MISSING |
| 字段级（FIELD_LEVEL） | 单个字段 | REQUIRED_MISSING, INVALID_TYPE, BOUNDARY_VALUE, INVALID_ENUM |

### 8.2 NORMAL_REQUEST_RULE

| 项 | 说明 |
|---|------|
| 级别 | API_LEVEL |
| 目标 | 为每个接口生成 1 条基准正常 case |
| matchCondition | 始终匹配 |
| 执行方式 | 直接复制 baseValidRequest |
| expectedStatusCode | 200 |

### 8.3 AUTH_MISSING_RULE

| 项 | 说明 |
|---|------|
| 级别 | API_LEVEL |
| 目标 | 生成缺少鉴权信息的 case |
| matchCondition | `authRequired = true` |
| 执行方式 | 移除 baseValidRequest 中的 auth header |
| expectedStatusCode | 401 / 403 |

### 8.4 REQUIRED_MISSING_RULE

| 项 | 说明 |
|---|------|
| 级别 | FIELD_LEVEL |
| 目标 | 针对必填字段生成"缺失字段" case |
| matchCondition | `required = true` |
| 执行方式 | 从 baseValidRequest 中移除该字段 |
| expectedStatusCode | 400 |

### 8.5 INVALID_TYPE_RULE

| 项 | 说明 |
|---|------|
| 级别 | FIELD_LEVEL |
| 目标 | 针对类型明确的字段生成"类型错误" case |
| matchCondition | 字段存在清晰 dataType |
| 执行方式 | string→填 int, int→填 string, boolean→填非法文本 |
| expectedStatusCode | 400 |

### 8.6 BOUNDARY_VALUE_RULE

| 项 | 说明 |
|---|------|
| 级别 | FIELD_LEVEL |
| 目标 | 针对数值和长度约束字段生成边界 case |
| matchCondition | 有 min/max 或 minLength/maxLength |
| 执行方式 | 生成 min, max, min-1, max+1（每字段 ≤ 2 条） |
| expectedStatusCode | min/max → 200; min-1/max+1 → 400 |

### 8.7 INVALID_ENUM_RULE

| 项 | 说明 |
|---|------|
| 级别 | FIELD_LEVEL |
| 目标 | 针对枚举字段生成非法枚举值 case |
| matchCondition | `enumValues` 非空 |
| 执行方式 | 生成不在枚举集合中的值 |
| expectedStatusCode | 400 |

## 9. 数量控制（三层预算）

### 9.1 执行顺序（写死在实现中，不可调换）

```
字段风险排序 → 规则预算筛选 → 接口总预算裁剪
```

### 9.2 第一层：字段风险排序

字段按风险优先级排序，保证有限预算分配给最有价值的字段：

| 优先级 | 字段类型 |
|:------:|---------|
| 1（最高） | 必填字段（required=true） |
| 2 | 业务主键字段（id, userId, orderId 等） |
| 3 | 有校验注解的字段（@Size, @Min, @Pattern 等） |
| 4 | 鉴权/状态/金额/枚举字段 |
| 5（最低） | 备注、描述等非核心字段 |

### 9.3 第二层：规则预算

| 规则 | 每接口最大条数 |
|------|:------------:|
| NORMAL_REQUEST_RULE | 1 |
| AUTH_MISSING_RULE | 1（仅在 authRequired=true 时） |
| REQUIRED_MISSING_RULE | ≤ 3（取风险排序 top 3 必填字段） |
| INVALID_TYPE_RULE | ≤ 3（取风险排序 top 3 字段） |
| BOUNDARY_VALUE_RULE | ≤ 2 字段 × ≤ 2 条/字段 = ≤ 4 |
| INVALID_ENUM_RULE | ≤ 2 字段 × 1 条/字段 = ≤ 2 |

### 9.4 第三层：接口总预算

```
maxCasesPerApi: 20（默认，可在 application.yml 覆盖）
```

**效果：大多数接口收敛在 10~15 条结构型 case。** 不是"先全生成再硬截断"，而是"按风险排序 → 规则预算筛选 → 总预算裁剪"。

### 9.5 单个字段的边界值裁剪

BOUNDARY_VALUE_RULE 理论上每个字段可出 4 条（min, max, min-1, max+1）。实际执行时：

- 优先保留 `min-1` 和 `max+1`（异常边界更有测试价值）
- 如果字段只有一方约束（如只有 min）→ 出 2 条
- 通过 `aggressiveBoundaryMode: false`（默认）控制

## 10. 去重策略

### 10.1 核心方式：结构级去重

```
dedupKey = caseType + targetField + normalizedInputPatch + expectedStatusCode
```

**不做 LLM 语义去重。** 规则系统本身输出已经高度结构化，结构级去重足够。

### 10.2 normalizedInputPatch 标准化规则

在计算 dedupKey 前，必须对 inputPatch 做标准化：

- JSON 字段按 key 字母序排列
- null 值和缺失字段统一表示为 `※MISSING※`
- 字段路径统一为点分隔格式（`body.user.name`、`query.pageNo`）
- 枚举值统一大写
- 数值类型统一（不区分 int/long 的具体差值）

### 10.3 辅助规则：等价合并

如果不同 caseType 对同一字段产生的变异效果完全等价，合并仅保留一条。例如：某平台将"字段缺失"和"字段为 null"视为等价，则 `REQUIRED_MISSING` 和 `NULL_VALUE` 只保留一条。

### 10.4 不做的

- LLM 语义去重
- 跨维度相似度计算
- 自动合并"看起来差不多"的 case

## 11. 规则配置化（application.yml）

### 11.1 第一版配置结构

```yaml
caseRule:
  enabledRules:
    - NORMAL_REQUEST_RULE
    - REQUIRED_MISSING_RULE
    - INVALID_TYPE_RULE
    - BOUNDARY_VALUE_RULE
    - INVALID_ENUM_RULE
    - AUTH_MISSING_RULE
  
  maxCasesPerApi: 20
  
  perRuleBudget:
    REQUIRED_MISSING_RULE: 3
    INVALID_TYPE_RULE: 3
    BOUNDARY_VALUE_RULE: 4
    INVALID_ENUM_RULE: 2

  boundaryMode:
    aggressive: false             # true=每字段出 4 条, false=每字段 ≤ 2 条
    enableNullCase: true          # 是否生成 null 值 case
    enableEmptyStringCase: true   # 是否生成空字符串 case

  fieldRiskWeights:
    requiredField: 5
    businessKeyField: 4
    validatedField: 3
    authStateAmountEnumField: 2
    otherField: 1
```

### 11.2 规则扩展性路线

| 阶段 | 能力 | 说明 |
|------|------|------|
| **V1** | Java 内置规则 + YAML 开关/预算 | 规则逻辑是 Java class，YAML 控制启停和预算 |
| **V1.5** | 插件式自定义 Rule | 实现 `RuleDefinition` 接口，注册进 RuleEngine。YAML 配置适用范围/优先级 |
| **V2** | DSL + AI 草案 | 规则定义 DSL。AI 辅助生成规则草案，人工审核后上线 |

## 12. 规则执行流程

### 12.1 主流程

```
1. ConstraintExtractor.extract(ApiSpec) → List<FieldConstraint>
2. RuleContextBuilder.build(apiSpec, constraints) → RuleContext
3. BaseValidRequestBuilder.build(ruleContext) → Map<String, Object>
   （按优先级链：exampleValue → defaultValue → 知识库 → ValueFactory → AI增强）
4. RuleEngine.execute(ruleContext):
   a. 接口级规则：
      - NORMAL_REQUEST_RULE.match → execute
      - AUTH_MISSING_RULE.match (if authRequired=true) → execute
   b. 字段级规则：
      - 按字段风险排序 → top N 字段
      - 对每个入选字段，按规则顺序执行：
        REQUIRED_MISSING → INVALID_TYPE → BOUNDARY_VALUE → INVALID_ENUM
      - 每轮检查规则预算是否耗尽
   c. 接口总预算截止
5. DraftDeduplicator.deduplicate(drafts) → 去重后 drafts
6. 输出 TestCaseDraft[]
```

### 12.2 规则执行顺序

```
1. NORMAL_REQUEST_RULE         (接口级)
2. AUTH_MISSING_RULE           (接口级，条件触发)
3. REQUIRED_MISSING_RULE       (字段级)
4. INVALID_TYPE_RULE           (字段级)
5. BOUNDARY_VALUE_RULE         (字段级)
6. INVALID_ENUM_RULE           (字段级)
7. DraftDeduplicator            (去重 + 裁剪)
```

## 13. 与各模块的关系

| 模块 | 关系 |
|------|------|
| 接口自动分析 | 依赖 ApiSpec（FieldConstraint 的来源） |
| 测试用例生成 | 规则系统是 StructureCaseGenerator 的底层引擎 |
| 执行引擎 | TestCaseDraft/TestCase 需被执行引擎直接消费；字段设计兼容 RequestBuilder + 变量解析 |
| 记忆系统 | 规则系统不依赖长期记忆做决策；可选使用记忆系统的项目默认值偏好做 ValueFactory 增强 |
| 断言系统 | caseType 可驱动自动补断言逻辑（NORMAL→200, AUTH_FAILURE→401/403） |

## 14. 第一版落地范围

### 包含

- 6 条内置规则（Java 实现）
- ConstraintExtractor + RuleContextBuilder
- BaseValidRequestBuilder（4 级优先级链 + AI 可选增强）
- ValueFactory（7 类值生成方法 + 特殊值支持）
- 三层预算控制（字段风险排序 → 规则预算 → 接口总预算）
- DraftDeduplicator（结构级去重 + normalizedInputPatch 标准化）
- YAML 配置（规则开关 + 预算 + 风险权重）

### 暂不包含

- 规则 DSL 平台
- 可视化规则编排界面
- 规则热更新中心
- 复杂表达式语言
- AI 运行时替代规则引擎
- LLM 语义去重

## 15. Java 包结构建议

```
com.xxx.agent.casegen.rule
com.xxx.agent.casegen.rule.engine        // RuleEngine
com.xxx.agent.casegen.rule.definition    // RuleDefinition 接口 + 6 个实现类
com.xxx.agent.casegen.rule.matcher       // RuleMatcher
com.xxx.agent.casegen.rule.executor      // RuleExecutor
com.xxx.agent.casegen.rule.constraint    // ConstraintExtractor
com.xxx.agent.casegen.rule.context       // RuleContextBuilder, BaseValidRequestBuilder
com.xxx.agent.casegen.rule.value         // ValueFactory
com.xxx.agent.casegen.rule.dedup         // DraftDeduplicator, InputPatchNormalizer
com.xxx.agent.casegen.rule.config        // RuleConfig (YAML 映射)
com.xxx.agent.casegen.rule.model         // FieldConstraint, RuleContext, TestCaseDraft
```

## 16. 一句话总结

规则系统的核心价值不是"智能"，而是：**把接口约束稳定翻译成基础测试点，用可解释、可执行、可配置的方式保证接口测试的底层覆盖率。**

最终方案：

```
baseValidRequest 本地稳定生成（AI 只增强）
+ 6 条内置规则（接口级 + 字段级）
+ 三层预算控制（风险排序 → 规则预算 → 总预算，大多数接口 10~15 条）
+ 结构级去重（dedupKey + normalizedInputPatch）
+ YAML 配置化（开关 + 预算，不用 DSL）
+ V1 Java 内置规则，V1.5 插件化，V2 DSL
```
